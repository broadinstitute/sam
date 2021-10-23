package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.OnSuccessMagnet._
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountSubjectId
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.api.StandardUserInfoDirectives._
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.google.{GoogleOpaqueTokenResolver, GoogleTokenInfo}
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.ExecutionContext
import scala.util.Try
import scala.util.matching.Regex

trait StandardUserInfoDirectives extends UserInfoDirectives with LazyLogging with SamRequestContextDirectives {
  implicit val executionContext: ExecutionContext
  val maybeGoogleOpaqueTokenResolver: Option[GoogleOpaqueTokenResolver] = None

  def requireUserInfo(samRequestContext: SamRequestContext): Directive1[UserInfo] = requireOidcHeaders.flatMap { oidcHeaders =>
    onSuccess {
      getUserInfo(directoryDAO, maybeGoogleOpaqueTokenResolver, oidcHeaders, samRequestContext).unsafeToFuture()
    }
  }

  def requireCreateUser(samRequestContext: SamRequestContext): Directive1[WorkbenchUser] =
    requireOidcHeaders.flatMap(buildWorkbenchUser(_, samRequestContext))

  private def buildWorkbenchUser(oidcHeaders: OIDCHeaders, samRequestContext: SamRequestContext): Directive1[WorkbenchUser] = {
    val googleSubjectId = oidcHeaders.externalId.left.toOption
    val azureB2CId = oidcHeaders.externalId.toOption // .right is missing (compared to .left above) since Either is Right biased
    val workbenchUser = WorkbenchUser(
      genWorkbenchUserId(System.currentTimeMillis()),
      googleSubjectId,
      oidcHeaders.email,
      azureB2CId)

    (oidcHeaders.externalId, oidcHeaders.idpAccessToken, maybeGoogleOpaqueTokenResolver) match {
      case (Right(azureB2CId), Some(accessToken), Some(googleOpaqueTokenResolver)) =>
        populateGoogleIdAndThrowIfUserExists(samRequestContext, accessToken, googleOpaqueTokenResolver, azureB2CId, workbenchUser)
      case _ => provide(workbenchUser)
    }
  }

  /**
    * If the oidc headers contain an azure b2c id and an idp access token and a GoogleOpaqueTokenResolver
    * is defined, resolve the opaque idp access token, populate the google subject id or
    * throw an error if it resolves to a user already within the system.
    * @param oidcHeaders
    * @return
    */
  private def populateGoogleIdAndThrowIfUserExists(samRequestContext: SamRequestContext, accessToken: OAuth2BearerToken, googleOpaqueTokenResolver: GoogleOpaqueTokenResolver, azureB2CId: AzureB2CId, workbenchUser: WorkbenchUser): Directive1[WorkbenchUser] = {
    onSuccess {
      googleOpaqueTokenResolver.getGoogleTokenInfo(accessToken, samRequestContext).flatMap {
        case None =>
          IO.pure(workbenchUser) // access token not valid
        case Some(GoogleTokenInfo(None, googleSubjectId)) => // access token valid but not already a user
          IO.pure(workbenchUser.copy(googleSubjectId = Option(googleSubjectId)))
        case Some(GoogleTokenInfo(Some(_), _)) => // already a user
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"user ${workbenchUser.email} already exists")))
      }.unsafeToFuture()
    }
  }

  /**
    * Utility function that knows how to convert all the various headers into OIDCHeaders
    */
  private def requireOidcHeaders: Directive1[OIDCHeaders] = {
    (headerValueByName(accessTokenHeader).as(OAuth2BearerToken) &
      externalIdFromHeaders &
      expiresInFromHeader &
      headerValueByName(emailHeader).as(WorkbenchEmail) &
      optionalHeaderValueByName(idpAccessTokenHeader).map(_.map(OAuth2BearerToken))).as(OIDCHeaders)
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
  val idpAccessTokenHeader = "OAUTH2_CLAIM_idp_access_token"

  def getUserInfo(directoryDAO: DirectoryDAO, maybeGoogleOpaqueTokenResolver: Option[GoogleOpaqueTokenResolver], oidcHeaders: OIDCHeaders, samRequestContext: SamRequestContext): IO[UserInfo] = {
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
        loadUserMaybeUpdateAzureB2CId(azureB2CId, oidcHeaders.idpAccessToken, directoryDAO, maybeGoogleOpaqueTokenResolver, samRequestContext).map(user => UserInfo(oidcHeaders.token, user.id, oidcHeaders.email, oidcHeaders.expiresIn))
    }
  }

  private def loadUserMaybeUpdateAzureB2CId(azureB2CId: AzureB2CId, maybeIdpToken: Option[OAuth2BearerToken], directoryDAO: DirectoryDAO, maybeGoogleOpaqueTokenResolver: Option[GoogleOpaqueTokenResolver], samRequestContext: SamRequestContext) = {
    for {
      maybeUser <- directoryDAO.loadUserByAzureB2CId(azureB2CId, samRequestContext)
      maybeUserAgain <- maybeUser match {
        case None => updateUserAzureB2CId(azureB2CId, maybeIdpToken, directoryDAO, maybeGoogleOpaqueTokenResolver, samRequestContext)
        case _ => IO.pure(maybeUser)
      }
    } yield maybeUserAgain.getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, s"Azure Id $azureB2CId not found in sam")))
  }

  private def updateUserAzureB2CId(azureB2CId: AzureB2CId, maybeIdpToken: Option[OAuth2BearerToken], directoryDAO: DirectoryDAO, maybeGoogleOpaqueTokenResolver: Option[GoogleOpaqueTokenResolver], samRequestContext: SamRequestContext) = {
    (maybeIdpToken, maybeGoogleOpaqueTokenResolver) match {
      case (Some(opaqueToken), Some(googleOpaqueTokenResolver)) => for {
        maybeGoogleTokenInfo <- googleOpaqueTokenResolver.getGoogleTokenInfo(opaqueToken, samRequestContext)
        _ <- maybeGoogleTokenInfo match {
          case Some(GoogleTokenInfo(Some(userId), _)) => directoryDAO.setUserAzureB2CId(userId, azureB2CId, samRequestContext)
          case _ => IO.unit
        }
        maybeUser <- directoryDAO.loadUserByAzureB2CId(azureB2CId, samRequestContext)
      } yield {
        maybeUser
      }
      case _ => IO.pure(None)
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
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, s"Google Id $googleSubjectId not found in sam")))
      }
    } yield userInfo
}

final case class OIDCHeaders(token: OAuth2BearerToken, externalId: Either[GoogleSubjectId, AzureB2CId], expiresIn: Long, email: WorkbenchEmail, idpAccessToken: Option[OAuth2BearerToken])
