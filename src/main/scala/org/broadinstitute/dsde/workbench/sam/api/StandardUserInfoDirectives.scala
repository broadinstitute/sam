package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.OnSuccessMagnet._
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountSubjectId
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, _}
import org.broadinstitute.dsde.workbench.sam.api.StandardUserInfoDirectives._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.service.UserService._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success, Try}
import pdi.jwt.{JwtOptions, JwtSprayJson}
import spray.json._
import java.time.Instant

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.sam.identityConcentrator.IdentityConcentratorService

import scala.util.matching.Regex
import DefaultJsonProtocol._
import WorkbenchIdentityJsonSupport.identityConcentratorIdFormat
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

trait StandardUserInfoDirectives extends UserInfoDirectives with LazyLogging with SamModelDirectives {
  implicit val executionContext: ExecutionContext
  val identityConcentratorService: Option[IdentityConcentratorService] = None

  def requireUserInfo(samRequestContext: SamRequestContext): Directive1[UserInfo] = handleAuthHeaders(requireUserInfoFromJwt, requireUserInfoFromOIDC, samRequestContext)
  def requireCreateUser(samRequestContext: SamRequestContext): Directive1[CreateWorkbenchUser] = handleAuthHeaders(requireCreateUserInfoFromJwt, requireCreateUserInfoFromOIDC, samRequestContext)

  /**
    * Utility function that knows how to handle the request based on whether or not JWT is present.
    *
    * @param fromJwt function to call when jwt is present
    * @param fromOIDC function to call when jwt is not present
    * @tparam T type this request returns
    * @return
    */
  private def handleAuthHeaders[T](fromJwt: (JwtUserInfo, OAuth2BearerToken, IdentityConcentratorService, SamRequestContext) => Directive1[T], fromOIDC: (OIDCHeaders, SamRequestContext) => Directive1[T], samRequestContext: SamRequestContext): Directive1[T] =
      // this causes 2 errors (on both `failWith` calls below) that look like this:
      //[error]  found   : akka.http.scaladsl.server.StandardRoute
      //[error]  required: akka.http.scaladsl.server.Directive[?]
    withParentSamRequestContext("handleAuthHeaders", samRequestContext) { samRequestContext =>
      (headerValueByName(authorizationHeader) &
        provide(identityConcentratorService)) tflatMap {
        // order of these cases is important: if the authorization header is a valid jwt we must ignore
        // any OIDC headers otherwise we may be vulnerable to a malicious user specifying a valid JWT
        // but different user information in the OIDC headers
        case (JwtAuthorizationHeader(Success(jwtUserInfo), bearerToken), Some(icService)) =>
          fromJwt(jwtUserInfo, bearerToken, icService, samRequestContext)

        case (JwtAuthorizationHeader(Failure(regrets), _), _) =>
          failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Unauthorized, "could not parse authorization header, jwt missing required fields", regrets)))

        case (JwtAuthorizationHeader(_, _), None) =>
          logger.error("requireUserInfo with JWT attempted but Identity Concentrator not configured")
          failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, "Identity Concentrator not configured")))

        case _ =>
          // request coming through Apache proxy with OIDC headers
          (headerValueByName(accessTokenHeader).as(OAuth2BearerToken) &
            headerValueByName(googleSubjectIdHeader).as(GoogleSubjectId) &
            headerValueByName(expiresInHeader) &
            headerValueByName(emailHeader).as(WorkbenchEmail)).as(OIDCHeaders).flatMap(fromOIDC(_, samRequestContext))
      }
    }

  private def requireUserInfoFromJwt(jwtUserInfo: JwtUserInfo, bearerToken: OAuth2BearerToken, icService: IdentityConcentratorService, samRequestContext: SamRequestContext): Directive1[UserInfo] =
    onSuccess {
      getUserInfoFromJwt(jwtUserInfo, bearerToken, directoryDAO, icService, samRequestContext).unsafeToFuture()
    }

  private def requireUserInfoFromOIDC(oidcHeaders: OIDCHeaders, samRequestContext: SamRequestContext): Directive1[UserInfo] =
    onSuccess {
      getUserInfoFromOidcHeaders(directoryDAO, oidcHeaders, samRequestContext).unsafeToFuture()
    }

  private def requireCreateUserInfoFromJwt(jwtUserInfo: JwtUserInfo, bearerToken: OAuth2BearerToken, icService: IdentityConcentratorService, samRequestContext: SamRequestContext): Directive1[CreateWorkbenchUser] =
    onSuccess {
      newCreateWorkbenchUserFromJwt(jwtUserInfo, bearerToken, icService).unsafeToFuture()
    }

  private def requireCreateUserInfoFromOIDC(oidcHeaders: OIDCHeaders, samRequestContext: SamRequestContext): Directive1[CreateWorkbenchUser] =
    provide(CreateWorkbenchUser(genWorkbenchUserId(System.currentTimeMillis()), oidcHeaders.googleSubjectId, oidcHeaders.email, None))
}

object StandardUserInfoDirectives {
  val SAdomain: Regex = "\\S+@\\S+\\.iam\\.gserviceaccount\\.com".r
  val accessTokenHeader = "OIDC_access_token"
  val expiresInHeader = "OIDC_CLAIM_expires_in"
  val emailHeader = "OIDC_CLAIM_email"
  val googleSubjectIdHeader = "OIDC_CLAIM_user_id"
  val authorizationHeader = "Authorization"

  implicit val jwtPayloadFormat: JsonFormat[JwtUserInfo] = jsonFormat2(JwtUserInfo)

  def isServiceAccount(email: WorkbenchEmail): Boolean =
    SAdomain.pattern.matcher(email.value).matches

  def getUserInfoFromOidcHeaders(directoryDAO: DirectoryDAO, oidcHeaders: OIDCHeaders, samRequestContext: SamRequestContext): IO[UserInfo] =
    Try(oidcHeaders.expiresIn.toLong).fold(
      t => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"expiresIn ${oidcHeaders.expiresIn} can't be converted to Long because  of $t"))),
      expiresInLong => getUserInfo(oidcHeaders.token, oidcHeaders.googleSubjectId, oidcHeaders.email, expiresInLong, directoryDAO, samRequestContext)
    )

  def getUserInfo(token: OAuth2BearerToken, googleSubjectId: GoogleSubjectId, email: WorkbenchEmail, expiresIn: Long, directoryDAO: DirectoryDAO, samRequestContext: SamRequestContext): IO[UserInfo] =
    if (isServiceAccount(email)) {
      // If it's a PET account, we treat it as its owner
      directoryDAO.getUserFromPetServiceAccount(ServiceAccountSubjectId(googleSubjectId.value), samRequestContext).flatMap {
        case Some(pet) => IO.pure(UserInfo(token, pet.id, pet.email, expiresIn.toLong))
        case None => lookUpByGoogleSubjectId(googleSubjectId, directoryDAO, samRequestContext).map(uid => UserInfo(token, uid, email, expiresIn))
      }
    } else {
      lookUpByGoogleSubjectId(googleSubjectId, directoryDAO, samRequestContext).map(uid => UserInfo(token, uid, email, expiresIn))
    }

  def getUserInfoFromJwt(jwtUserInfo: JwtUserInfo, bearerToken: OAuth2BearerToken, directoryDAO: DirectoryDAO, identityConcentratorService: IdentityConcentratorService, samRequestContext: SamRequestContext): IO[UserInfo] = {
    for {
      maybeUser <- loadUserMaybeUpdateIdentityConcentratorId(jwtUserInfo, bearerToken, directoryDAO, identityConcentratorService, samRequestContext)
      user <- maybeUser match {
        case Some(user) => IO.pure(UserInfo(bearerToken, user.id, user.email, jwtUserInfo.exp - Instant.now().getEpochSecond))
        case None =>
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Identity Concentrator Id ${jwtUserInfo.sub} not found in sam")))
      }
    } yield user
  }

  private def loadUserMaybeUpdateIdentityConcentratorId(jwtUserInfo: JwtUserInfo, bearerToken: OAuth2BearerToken, directoryDAO: DirectoryDAO, identityConcentratorService: IdentityConcentratorService, samRequestContext: SamRequestContext): IO[Option[WorkbenchUser]] = {
    for {
      maybeUser <- directoryDAO.loadUserByIdentityConcentratorId(jwtUserInfo.sub, samRequestContext)
      maybeUserAgain <- maybeUser match {
        case None => updateUserIdentityConcentratorId(jwtUserInfo, bearerToken, directoryDAO, identityConcentratorService, samRequestContext)
        case _ => IO.pure(maybeUser)
      }
    } yield maybeUserAgain
  }

  private def updateUserIdentityConcentratorId(jwtUserInfo: JwtUserInfo, bearerToken: OAuth2BearerToken, directoryDAO: DirectoryDAO, identityConcentratorService: IdentityConcentratorService, samRequestContext: SamRequestContext): IO[Option[WorkbenchUser]] = {
    for {
      googleIdentities <- identityConcentratorService.getGoogleIdentities(bearerToken)
      (googleSubjectId, _) <- singleGoogleIdentity(jwtUserInfo.sub, googleIdentities)
      _ <- directoryDAO.setUserIdentityConcentratorId(googleSubjectId, jwtUserInfo.sub, samRequestContext)
      maybeUser <- directoryDAO.loadUserByIdentityConcentratorId(jwtUserInfo.sub, samRequestContext)
    } yield maybeUser
  }

  def singleGoogleIdentity(identityConcentratorId: IdentityConcentratorId, googleIdentities: Seq[(GoogleSubjectId, WorkbenchEmail)]): IO[(GoogleSubjectId, WorkbenchEmail)] =
    googleIdentities match {
      case Seq() =>
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, s"no linked google identities for $identityConcentratorId")))
      case Seq(singleIdentity) => IO.pure(singleIdentity)
      case _ =>
        IO.raiseError(new WorkbenchExceptionWithErrorReport(
          ErrorReport(StatusCodes.InternalServerError, s"too many linked google identities for $identityConcentratorId: ${googleIdentities.mkString("'")}")))
    }

  private def lookUpByGoogleSubjectId(googleSubjectId: GoogleSubjectId, directoryDAO: DirectoryDAO, samRequestContext: SamRequestContext): IO[WorkbenchUserId] =
    for {
      subject <- directoryDAO.loadSubjectFromGoogleSubjectId(googleSubjectId, samRequestContext)
      userInfo <- subject match {
        case Some(uid: WorkbenchUserId) => IO.pure(uid)
        case Some(_) =>
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"subjectId $googleSubjectId is not a WorkbenchUser")))
        case None =>
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"google subject Id $googleSubjectId not found in sam")))
      }
    } yield userInfo

  def newCreateWorkbenchUserFromJwt(jwtUserInfo: JwtUserInfo, bearerToken: OAuth2BearerToken, icService: IdentityConcentratorService): IO[CreateWorkbenchUser] =
    for {
      googleIdentities <- icService.getGoogleIdentities(bearerToken)
      (googleSubjectId, email) <- singleGoogleIdentity(jwtUserInfo.sub, googleIdentities)
    } yield CreateWorkbenchUser(genWorkbenchUserId(System.currentTimeMillis()), googleSubjectId, email, Option(jwtUserInfo.sub))
}

final case class CreateWorkbenchUser(id: WorkbenchUserId, googleSubjectId: GoogleSubjectId, email: WorkbenchEmail, identityConcentratorId: Option[IdentityConcentratorId])

final case class JwtUserInfo(sub: IdentityConcentratorId, exp: Long)

final case class OIDCHeaders(token: OAuth2BearerToken, googleSubjectId: GoogleSubjectId, expiresIn: String, email: WorkbenchEmail)

object JwtAuthorizationHeader extends LazyLogging {
  /**
    * Pattern matcher for JWT authorization header. Expected cases:
    * - not a jwt or bearer token: return None
    * - is a jwt but missing required fields: return Some(Failure, OAuth2BearerToken)
    * - is a jwt that is parsable to JwtUserInfo: return Some(Success(JwtUserInfo), OAuth2BearerToken)
    *
    * @param authorizationHeader unprocessed value of the Authorization http header
    * @return None if the header is not a bearer token or is not a parsable JWT.
    * Otherwise it will Try to parse the JWT payload to a JwtUserInfo and an OAuth2BearerToken.
    */
  def unapply(authorizationHeader: String): Option[(Try[JwtUserInfo], OAuth2BearerToken)] = {
    for {
      bearerToken <- getBearerTokenFromAuthHeader(authorizationHeader)
      jwtJson <- parseJwtBearerToken(bearerToken)
    } yield (Try(jwtJson.convertTo[JwtUserInfo]), bearerToken)
  }

  private def getBearerTokenFromAuthHeader(authorizationHeader: String): Option[OAuth2BearerToken] = {
    val bearerPrefix = "bearer "
    // need to be case insensitive when looking for the 'bearer' prefix so can't use stripPrefix
    if (authorizationHeader.toLowerCase.startsWith(bearerPrefix)) {
      Option(OAuth2BearerToken(authorizationHeader.drop(bearerPrefix.length)))
    } else {
      logger.debug("authorization header not a bearer token")
      None
    }
  }

  private def parseJwtBearerToken(bearerToken: OAuth2BearerToken): Option[JsObject] = {
    // Assumption: JWT is already validated
    JwtSprayJson.decodeJson(bearerToken.token, JwtOptions(signature = false, expiration = false)) match {
      case Failure(t) =>
        logger.debug("could not parse authorization header, invalid jwt", t)
        None

      case Success(json) => Option(json)
    }
  }
}
