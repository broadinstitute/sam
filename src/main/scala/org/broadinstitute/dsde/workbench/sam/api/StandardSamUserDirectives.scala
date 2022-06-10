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
import org.broadinstitute.dsde.workbench.sam.api.StandardSamUserDirectives._
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, RegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.broadinstitute.dsde.workbench.sam.service.TosService
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.ExecutionContext
import scala.util.Try
import scala.util.matching.Regex

trait StandardSamUserDirectives extends SamUserDirectives with LazyLogging with SamRequestContextDirectives {
  implicit val executionContext: ExecutionContext

  def withActiveUser(samRequestContext: SamRequestContext): Directive1[SamUser] = requireOidcHeaders.flatMap { oidcHeaders =>
    onSuccess {
      getActiveSamUser(oidcHeaders, directoryDAO, registrationDAO, tosService, samRequestContext).unsafeToFuture()
    }
  }

  def withUserAllowInactive(samRequestContext: SamRequestContext): Directive1[SamUser] = requireOidcHeaders.flatMap { oidcHeaders =>
    onSuccess {
      getSamUser(oidcHeaders, directoryDAO, registrationDAO, samRequestContext).unsafeToFuture()
    }
  }

  def withNewUser(samRequestContext: SamRequestContext): Directive1[SamUser] = requireOidcHeaders.map(buildSamUser)

  private def buildSamUser(oidcHeaders: OIDCHeaders): SamUser = {
    // google id can either be in the external id or google id from azure headers, favor the external id as the source
    val googleSubjectId = (oidcHeaders.externalId.left.toOption ++ oidcHeaders.googleSubjectIdFromAzure).headOption
    val azureB2CId = oidcHeaders.externalId.toOption // .right is missing (compared to .left above) since Either is Right biased

    SamUser(
      genWorkbenchUserId(System.currentTimeMillis()),
      googleSubjectId,
      oidcHeaders.email,
      azureB2CId,
      false,
      None)
  }

  /**
    * Utility function that knows how to convert all the various headers into OIDCHeaders
    */
  private def requireOidcHeaders: Directive1[OIDCHeaders] = {
    (headerValueByName(accessTokenHeader).as(OAuth2BearerToken) &
      externalIdFromHeaders &
      headerValueByName(emailHeader).as(WorkbenchEmail) &
      optionalHeaderValueByName(googleIdFromAzureHeader).map(_.map(GoogleSubjectId))).as(OIDCHeaders)
  }

  private def externalIdFromHeaders: Directive1[Either[GoogleSubjectId, AzureB2CId]] = headerValueByName(userIdHeader).map { idString =>
    Try(BigInt(idString)).fold(
      _ => Right(AzureB2CId(idString)),    // could not parse id as a Long, treat id as b2c id which are uuids
      _ => Left(GoogleSubjectId(idString)) // id is a number which is what google subject ids look like
    )
  }
}

object StandardSamUserDirectives {
  val SAdomain: Regex = "(\\S+@\\S+\\.iam\\.gserviceaccount\\.com$)".r
  val accessTokenHeader = "OIDC_access_token"
  val emailHeader = "OIDC_CLAIM_email"
  val userIdHeader = "OIDC_CLAIM_user_id"
  val googleIdFromAzureHeader = "OAUTH2_CLAIM_google_id"

  def getSamUser(oidcHeaders: OIDCHeaders, directoryDAO: DirectoryDAO, registrationDAO: RegistrationDAO, samRequestContext: SamRequestContext): IO[SamUser] = {
    oidcHeaders match {
      case OIDCHeaders(_, Left(googleSubjectId), WorkbenchEmail(SAdomain(_)), _) =>
        // If it's a PET account, we treat it as its owner
        directoryDAO.getUserFromPetServiceAccount(ServiceAccountSubjectId(googleSubjectId.value), samRequestContext).flatMap {
          case Some(petsOwner) => IO.pure(petsOwner)
          case None => lookUpByGoogleSubjectId(googleSubjectId, directoryDAO, samRequestContext)
        }

      case OIDCHeaders(_, Left(googleSubjectId), _, _) =>
        lookUpByGoogleSubjectId(googleSubjectId, directoryDAO, samRequestContext)

      case OIDCHeaders(_, Right(azureB2CId), _, _) =>
        loadUserMaybeUpdateAzureB2CId(azureB2CId, oidcHeaders.googleSubjectIdFromAzure, directoryDAO, registrationDAO, samRequestContext)
    }
  }

  def getActiveSamUser(oidcHeaders: OIDCHeaders, directoryDAO: DirectoryDAO, registrationDAO: RegistrationDAO, tosService: TosService, samRequestContext: SamRequestContext): IO[SamUser] = {
    for {
      user <- getSamUser(oidcHeaders, directoryDAO, registrationDAO, samRequestContext)
    } yield {
      // service account users do not need to accept ToS
      val tosStatusAcceptable = tosService.isTermsOfServiceStatusAcceptable(user) || SAdomain.matches(user.email.value)
      if (!tosStatusAcceptable) {
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Unauthorized, "User has not accepted the terms of service."))
      }
      if (!user.enabled) {
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Unauthorized, "User is disabled."))
      }

      user
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
    } yield maybeUserAgain.getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, s"Azure Id $azureB2CId not found in sam")))
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

  private def lookUpByGoogleSubjectId(googleSubjectId: GoogleSubjectId, directoryDAO: DirectoryDAO, samRequestContext: SamRequestContext): IO[SamUser] =
    directoryDAO.loadUserByGoogleSubjectId(googleSubjectId, samRequestContext).flatMap { maybeUser =>
      IO.fromOption(maybeUser)(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, s"Google Id $googleSubjectId not found in sam")))
    }
}

final case class OIDCHeaders(token: OAuth2BearerToken, externalId: Either[GoogleSubjectId, AzureB2CId], email: WorkbenchEmail, googleSubjectIdFromAzure: Option[GoogleSubjectId])
