package org.broadinstitute.dsde.workbench.sam
package api

import akka.event.LoggingAdapter
import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RouteResult.{Complete, Rejected}
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.{DebuggingDirectives, LoggingMagnet}
import akka.http.scaladsl.server.directives.OnSuccessMagnet._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountSubjectId
import org.broadinstitute.dsde.workbench.sam.api.StandardSamUserDirectives._
import org.broadinstitute.dsde.workbench.sam.azure.ManagedIdentityObjectId
import org.broadinstitute.dsde.workbench.sam.config.TermsOfServiceConfig
import org.broadinstitute.dsde.workbench.sam.metrics.{
  MetricsLoggable,
  RegisteredUserApiEvent,
  RequestEventDetails,
  ResponseEventDetails,
  UnregisteredUserApiEvent
}
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.broadinstitute.dsde.workbench.sam.service.UserService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.Try
import scala.util.matching.Regex

trait StandardSamUserDirectives extends SamUserDirectives with LazyLogging with SamRequestContextDirectives {
  implicit val executionContext: ExecutionContext

  def withActiveUser(samRequestContext: SamRequestContext): Directive1[SamUser] = requireOidcHeaders.flatMap { oidcHeaders =>
    onSuccess {
      getActiveSamUser(oidcHeaders, userService, termsOfServiceConfig, samRequestContext).unsafeToFuture()
    }.flatMap { samUser =>
      logger.debug(s"Handling request for active Sam User: $samUser")
      logRequestResultWithSamUser(samUser, oidcHeaders)
    }
  }

  def asAdminServiceUser: Directive0 = requireOidcHeaders.flatMap { oidcHeaders =>
    Directives
      .mapInnerRoute { r =>
        if (!adminConfig.serviceAccountAdmins.contains(oidcHeaders.email)) {
          reject(AuthorizationFailedRejection)
        } else {
          logger.info(s"Handling request for service admin account: ${oidcHeaders.email}")
          r
        }
      }
      .tflatMap(_ => logAdminServiceAdminUserRequestResult(oidcHeaders))
  }

  def withUserAllowInactive(samRequestContext: SamRequestContext): Directive1[SamUser] = requireOidcHeaders.flatMap { oidcHeaders =>
    onSuccess {
      getSamUser(oidcHeaders, userService, samRequestContext).unsafeToFuture()
    }.flatMap { samUser =>
      logger.debug(s"Handling request for (in)active Sam User: $samUser")
      logRequestResultWithSamUser(samUser, oidcHeaders)
    }
  }

  def withNewUser(samRequestContext: SamRequestContext): Directive1[SamUser] = requireOidcHeaders.flatMap { oidcHeaders =>
    onSuccess {
      Future.successful(buildSamUser(oidcHeaders))
    }.flatMap { samUser =>
      logger.debug(s"Handling request for new Sam User: $samUser")
      logRequestResultWithSamUser(samUser, oidcHeaders)
    }
  }

  private def buildSamUser(oidcHeaders: OIDCHeaders): SamUser = {
    // google id can either be in the external id or google id from azure headers, favor the external id as the source
    val googleSubjectId = (oidcHeaders.externalId.left.toOption ++ oidcHeaders.googleSubjectIdFromAzure).headOption
    val azureB2CId = oidcHeaders.externalId.toOption // .right is missing (compared to .left above) since Either is Right biased

    SamUser(genWorkbenchUserId(System.currentTimeMillis()), googleSubjectId, oidcHeaders.email, azureB2CId, false)
  }

  /** Utility function that knows how to convert all the various headers into OIDCHeaders
    */
  private def requireOidcHeaders: Directive1[OIDCHeaders] =
    (headerValueByName(accessTokenHeader).as(OAuth2BearerToken) &
      externalIdFromHeaders &
      headerValueByName(emailHeader).as(WorkbenchEmail) &
      optionalHeaderValueByName(googleIdFromAzureHeader).map(_.map(GoogleSubjectId)) &
      optionalHeaderValueByName(managedIdentityObjectIdHeader).map(_.map(ManagedIdentityObjectId)))
      .as(OIDCHeaders)
      .map { oidcHeaders =>
        logger.debug(s"Auth Headers: $oidcHeaders")
        oidcHeaders
      }

  private def externalIdFromHeaders: Directive1[Either[GoogleSubjectId, AzureB2CId]] = headerValueByName(userIdHeader).map { idString =>
    Try(BigInt(idString)).fold(
      _ => Right(AzureB2CId(idString)), // could not parse id as a Long, treat id as b2c id which are uuids
      _ => Left(GoogleSubjectId(idString)) // id is a number which is what google subject ids look like
    )
  }

  private def logAdminServiceAdminUserRequestResult(oidcHeaders: OIDCHeaders): Directive0 = {
    def logRequest(unusedLogger: LoggingAdapter)(req: HttpRequest)(res: RouteResult): Unit =
      res match {
        case Complete(resp) =>
          logger.info(
            s"Request from Service Admin user ${oidcHeaders.email}",
            UnregisteredUserApiEvent(
              "apiRequest:serviceAdmin:complete",
              RequestEventDetails(req, Some(oidcHeaders)),
              Option(ResponseEventDetails(resp))
            ).toStructuredArguments
          )
        case Rejected(rejections) =>
          logger.warn(
            s"Request from Service Admin user ${oidcHeaders.email}",
            UnregisteredUserApiEvent(
              "apiRequest:serviceAdmin:incomplete",
              RequestEventDetails(req, Some(oidcHeaders)),
              None,
              rejections
            ).toStructuredArguments
          )
      }

    DebuggingDirectives.logRequestResult(LoggingMagnet(log => logRequest(log)))
  }

  private def logRequestResultWithSamUser(samUser: SamUser, oidcHeaders: OIDCHeaders): Directive1[SamUser] = {

    def logSamUserRequest(unusedLogger: LoggingAdapter)(req: HttpRequest)(res: RouteResult): Unit =
      res match {
        case Complete(resp) =>
          logger.info(
            s"Request from user ${samUser.id} (${samUser.email})",
            RegisteredUserApiEvent(
              samUser.id,
              "apiRequest:user:complete",
              RequestEventDetails(req, Some(oidcHeaders)),
              Option(ResponseEventDetails(resp))
            ).toStructuredArguments
          )
        case Rejected(rejections) =>
          logger.warn(
            s"Request from user ${samUser.id} (${samUser.email})",
            RegisteredUserApiEvent(
              samUser.id,
              "apiRequest:user:incomplete",
              RequestEventDetails(req, Some(oidcHeaders)),
              None,
              rejections
            ).toStructuredArguments
          )
      }

    DebuggingDirectives.logRequestResult(LoggingMagnet(log => logSamUserRequest(log))).tmap(_ => samUser)
  }
}

object StandardSamUserDirectives {
  val SAdomain: Regex = "(\\S+@\\S*gserviceaccount\\.com$)".r
  val UAMIdomain: Regex = "(\\S+@\\S*uami\\.terra\\.bio$)".r
  // UAMI == "User Assigned Managed Identity" in Azure
  val UamiPattern: Regex = "(^/subscriptions/\\S+/resourcegroups/\\S+/providers/Microsoft\\.ManagedIdentity/userAssignedIdentities/\\S+$)".r
  val accessTokenHeader = "OIDC_access_token"
  val emailHeader = "OIDC_CLAIM_email"
  val userIdHeader = "OIDC_CLAIM_user_id"
  val googleIdFromAzureHeader = "OAUTH2_CLAIM_google_id"
  val managedIdentityObjectIdHeader = "OAUTH2_CLAIM_xms_mirid"

  def getSamUser(oidcHeaders: OIDCHeaders, userService: UserService, samRequestContext: SamRequestContext): IO[SamUser] =
    oidcHeaders match {
      case OIDCHeaders(_, Left(googleSubjectId), WorkbenchEmail(SAdomain(_)), _, _) =>
        // If it's a PET account, we treat it as its owner
        userService.getUserFromPetServiceAccount(ServiceAccountSubjectId(googleSubjectId.value), samRequestContext).flatMap {
          case Some(petsOwner) => IO.pure(petsOwner)
          case None => lookUpByGoogleSubjectId(googleSubjectId, userService, samRequestContext)
        }

      case OIDCHeaders(_, Left(googleSubjectId), _, _, _) =>
        lookUpByGoogleSubjectId(googleSubjectId, userService, samRequestContext)

      case OIDCHeaders(_, Right(azureB2CId), _, _, Some(objectId @ ManagedIdentityObjectId(UamiPattern(_)))) =>
        // If it's a managed identity, treat it as its owner
        userService.getUserFromPetManagedIdentity(objectId, samRequestContext).flatMap {
          case Some(petsOwner) => IO.pure(petsOwner)
          case None => loadUserMaybeUpdateAzureB2CId(azureB2CId, oidcHeaders.googleSubjectIdFromAzure, userService, samRequestContext)
        }

      case OIDCHeaders(_, Right(azureB2CId), _, _, _) =>
        loadUserMaybeUpdateAzureB2CId(azureB2CId, oidcHeaders.googleSubjectIdFromAzure, userService, samRequestContext)
    }

  def getActiveSamUser(
      oidcHeaders: OIDCHeaders,
      userService: UserService,
      termsOfServiceConfig: TermsOfServiceConfig,
      samRequestContext: SamRequestContext
  ): IO[SamUser] =
    for {
      user <- getSamUser(oidcHeaders, userService, samRequestContext)
      allowances <- userService.getUserAllowances(user, samRequestContext)
    } yield {
      if (!allowances.getTermsOfServiceCompliance) {
        val goToUrl = termsOfServiceConfig.acceptanceUrl.map(url => s" Please go to $url to accept the latest terms of service.").getOrElse("")
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Unauthorized, s"User must accept the latest terms of service.$goToUrl"))
      }
      if (!allowances.getEnabled) {
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Unauthorized, "User is disabled."))
      }

      user
    }

  private def loadUserMaybeUpdateAzureB2CId(
      azureB2CId: AzureB2CId,
      maybeGoogleSubjectId: Option[GoogleSubjectId],
      userService: UserService,
      samRequestContext: SamRequestContext
  ) =
    for {
      maybeUser <- userService.getUserByAzureB2CId(azureB2CId, samRequestContext)
      maybeUserAgain <- (maybeUser, maybeGoogleSubjectId) match {
        case (None, Some(googleSubjectId)) =>
          updateUserAzureB2CId(azureB2CId, googleSubjectId, userService, samRequestContext)
        case _ => IO.pure(maybeUser)
      }
    } yield maybeUserAgain.getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, s"Azure Id $azureB2CId not found in sam")))

  private def updateUserAzureB2CId(azureB2CId: AzureB2CId, googleSubjectId: GoogleSubjectId, userService: UserService, samRequestContext: SamRequestContext) =
    for {
      maybeSubject <- userService.getSubjectFromGoogleSubjectId(googleSubjectId, samRequestContext)
      _ <- maybeSubject match {
        case Some(userId: WorkbenchUserId) =>
          userService.setUserAzureB2CId(userId, azureB2CId, samRequestContext)
        case _ => IO.unit
      }
      maybeUser <- userService.getUserByAzureB2CId(azureB2CId, samRequestContext)
    } yield maybeUser

  private def lookUpByGoogleSubjectId(googleSubjectId: GoogleSubjectId, userService: UserService, samRequestContext: SamRequestContext): IO[SamUser] =
    userService.getUserFromGoogleSubjectId(googleSubjectId, samRequestContext).flatMap { maybeUser =>
      IO.fromOption(maybeUser)(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, s"Google Id $googleSubjectId not found in sam")))
    }
}

final case class OIDCHeaders(
    token: OAuth2BearerToken,
    externalId: Either[GoogleSubjectId, AzureB2CId],
    email: WorkbenchEmail,
    googleSubjectIdFromAzure: Option[GoogleSubjectId],
    managedIdentityObjectId: Option[ManagedIdentityObjectId] = None
) extends MetricsLoggable {

  override def toLoggableMap: java.util.Map[String, Any] = Map[String, Any](
    "googleSubjectId" -> externalId.left.toOption.map(_.value).orNull,
    "azureB2CId" -> externalId.map(_.value).toOption.orNull,
    "email" -> email.value,
    "googleSubjectIdFromAzure" -> googleSubjectIdFromAzure.map(_.value).orNull,
    "managedIdentityObjectId" -> managedIdentityObjectId.map(_.value).orNull
  ).asJava

  // Customized toString method so that fields are labeled and we must ensure that we do not log the Bearer Token
  override def toString: String = {
    val extId = externalId match {
      case Left(googleSubjectId) => s"GoogleSubjectId($googleSubjectId)"
      case Right(azureB2CId) => s"AzureB2CId($azureB2CId)"
    }
    s"OIDCHeaders(" +
      s"externalId: $extId, " +
      s"email: $email, " +
      s"googleSubjectIdFromAzure: $googleSubjectIdFromAzure, " +
      s"managedIdentityObjectId: $managedIdentityObjectId)"
  }
}
