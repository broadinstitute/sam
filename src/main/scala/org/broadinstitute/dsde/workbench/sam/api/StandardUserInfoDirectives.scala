package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.OnSuccessMagnet._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountSubjectId
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.api.StandardUserInfoDirectives._
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, RegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.ExecutionContext
import scala.util.Try
import scala.util.matching.Regex

trait StandardUserInfoDirectives extends UserInfoDirectives with LazyLogging with SamRequestContextDirectives {
  implicit val executionContext: ExecutionContext

  def requireUserInfo(samRequestContext: SamRequestContext): Directive1[UserInfo] = requireOidcHeaders.flatMap { oidcHeaders =>
    onSuccess {
      getUserInfo(directoryDAO, registrationDAO, oidcHeaders, samRequestContext).unsafeToFuture()
    }
  }

  def requireCreateUser(samRequestContext: SamRequestContext): Directive1[WorkbenchUser] = requireOidcHeaders.map(buildWorkbenchUser)

  private def buildWorkbenchUser(oidcHeaders: OIDCHeaders): WorkbenchUser = {
    // google id can either be in the external id or google id from azure headers, favor the external id as the source
    val googleSubjectId = (oidcHeaders.externalId.left.toOption ++ oidcHeaders.googleSubjectIdFromAzure).headOption
    val azureB2CId = oidcHeaders.externalId.toOption // .right is missing (compared to .left above) since Either is Right biased

    WorkbenchUser(
      genWorkbenchUserId(System.currentTimeMillis()),
      googleSubjectId,
      oidcHeaders.email,
      azureB2CId)
  }

  /**
    * Utility function that knows how to convert all the various headers into OIDCHeaders
    */
  private def requireOidcHeaders: Directive1[OIDCHeaders] = {
    (headerValueByName(accessTokenHeader).as(OAuth2BearerToken) &
      externalIdFromHeaders &
      expiresInFromHeader &
      headerValueByName(emailHeader).as(WorkbenchEmail) &
      optionalHeaderValueByName(googleIdFromAzureHeader).map(_.map(GoogleSubjectId))).as(OIDCHeaders)
  }

  private def expiresInFromHeader: Directive1[Long] = {
    // gets expiresInHeader as a string and converts it to Long raising an exception if it can't
    optionalHeaderValueByName(expiresInHeader).flatMap {
      case Some(expiresInString) =>
        Try(expiresInString.toLong).fold(
          t => failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"expiresIn $expiresInString can't be converted to Long", t))).toDirective,
          expiresInLong => provide(expiresInLong)
        )

      case None => provide(0)
    }
  }

  private def externalIdFromHeaders: Directive1[Either[GoogleSubjectId, AzureB2CId]] = headerValueByName(userIdHeader).map { idString =>
    Try(BigInt(idString)).fold(
      _ => Right(AzureB2CId(idString)),    // could not parse id as a Long, treat id as b2c id which are uuids
      _ => Left(GoogleSubjectId(idString)) // id is a number which is what google subject ids look like
    )
  }
}

object StandardUserInfoDirectives {
  val SAdomain: Regex = "(\\S+@\\S+\\.iam\\.gserviceaccount\\.com$)".r
  val accessTokenHeader = "OIDC_access_token"
  val expiresInHeader = "OIDC_CLAIM_expires_in"
  val emailHeader = "OIDC_CLAIM_email"
  val userIdHeader = "OIDC_CLAIM_user_id"
  val googleIdFromAzureHeader = "OAUTH2_CLAIM_google_id"

  def getUserInfo(directoryDAO: DirectoryDAO, registrationDAO: RegistrationDAO, oidcHeaders: OIDCHeaders, samRequestContext: SamRequestContext): IO[UserInfo] = {
    oidcHeaders match {
      case OIDCHeaders(_, Left(googleSubjectId), _, saEmail@WorkbenchEmail(SAdomain(_)), _) =>
        // If it's a PET account, we treat it as its owner
        directoryDAO.getUserFromPetServiceAccount(ServiceAccountSubjectId(googleSubjectId.value), samRequestContext).flatMap {
          case Some(pet) => IO.pure(UserInfo(oidcHeaders.token, pet.id, pet.email, oidcHeaders.expiresIn))
          case None => lookUpByGoogleSubjectId(googleSubjectId, directoryDAO, samRequestContext).map(uid => UserInfo(oidcHeaders.token, uid, saEmail, oidcHeaders.expiresIn))
        }

      case OIDCHeaders(_, Left(googleSubjectId), _, _, _) =>
        lookUpByGoogleSubjectId(googleSubjectId, directoryDAO, samRequestContext).map(uid => UserInfo(oidcHeaders.token, uid, oidcHeaders.email, oidcHeaders.expiresIn))

      case OIDCHeaders(_, Right(azureB2CId), _, _, _) =>
        loadUserMaybeUpdateAzureB2CId(azureB2CId, oidcHeaders.googleSubjectIdFromAzure, directoryDAO, registrationDAO, samRequestContext).map(user => UserInfo(oidcHeaders.token, user.id, oidcHeaders.email, oidcHeaders.expiresIn))
    }
  }

  private def loadUserMaybeUpdateAzureB2CId(azureB2CId: AzureB2CId, maybeGoogleSubjectId: Option[GoogleSubjectId], directoryDAO: DirectoryDAO, registrationDAO: RegistrationDAO, samRequestContext: SamRequestContext) = {
    for {
      maybeUser <- directoryDAO.loadUserByAzureB2CId(azureB2CId, samRequestContext)
      maybeUserAgain <- (maybeUser, maybeGoogleSubjectId) match {
        case (None, Some(googleSubjectId)) =>
          updateUserAzureB2CId(azureB2CId, googleSubjectId, directoryDAO, registrationDAO, samRequestContext)
        case _ => IO.pure(maybeUser)
      }
    } yield maybeUserAgain.getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, s"Azure Id $azureB2CId not found in sam or user is disabled")))
  }

  private def updateUserAzureB2CId(azureB2CId: AzureB2CId, googleSubjectId: GoogleSubjectId, directoryDAO: DirectoryDAO, registrationDAO: RegistrationDAO, samRequestContext: SamRequestContext) = {
    for {
      maybeSubject <- directoryDAO.loadSubjectFromGoogleSubjectId(googleSubjectId, samRequestContext)
      _ <- maybeSubject match {
        case Some(userId: WorkbenchUserId) =>
          directoryDAO.setUserAzureB2CId(userId, azureB2CId, samRequestContext)
            .flatMap(_ => registrationDAO.setUserAzureB2CId(userId, azureB2CId, samRequestContext))
        case _ => IO.unit
      }
      maybeUser <- directoryDAO.loadUserByAzureB2CId(azureB2CId, samRequestContext)
    } yield {
      maybeUser
    }
  }

  private def lookUpByGoogleSubjectId(googleSubjectId: GoogleSubjectId, directoryDAO: DirectoryDAO, samRequestContext: SamRequestContext): IO[WorkbenchUserId] =
    for {
      subject <- directoryDAO.loadSubjectFromGoogleSubjectId(googleSubjectId, samRequestContext)
      userInfo <- subject match {
        case Some(uid: WorkbenchUserId) => IO.pure(uid)
        case Some(_) =>
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"subjectId $googleSubjectId is not a WorkbenchUser")))
        case None =>
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, s"Google Id $googleSubjectId not found in sam or is disabled")))
      }
    } yield userInfo
}

final case class OIDCHeaders(token: OAuth2BearerToken, externalId: Either[GoogleSubjectId, AzureB2CId], expiresIn: Long, email: WorkbenchEmail, googleSubjectIdFromAzure: Option[GoogleSubjectId])
