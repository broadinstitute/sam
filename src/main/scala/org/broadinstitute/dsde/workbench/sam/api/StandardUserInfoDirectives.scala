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

trait StandardUserInfoDirectives extends UserInfoDirectives with LazyLogging {
  implicit val executionContext: ExecutionContext
  val identityConcentratorService: Option[IdentityConcentratorService] = None

  def requireUserInfo: Directive1[UserInfo] =
    (optionalHeaderValueByName(accessTokenHeader) &
      optionalHeaderValueByName(googleSubjectIdHeader) &
      optionalHeaderValueByName(expiresInHeader) &
      optionalHeaderValueByName(emailHeader) &
      headerValueByName(authorizationHeader) &
      provide(identityConcentratorService)
    ) tflatMap {
      // order of these cases is important: if the authorization header is a valid jwt we must ignore
      // any OIDC headers otherwise we may be vulnerable to a malicious user specifying a valid JWT
      // but different user information in the OIDC headers
      case (_, _, _, _, JwtAuthorizationHeader((jwtJson, bearerToken)), Some(icService)) =>
        onSuccess {
          getUserInfoFromJwt(jwtJson, bearerToken, directoryDAO, icService).unsafeToFuture()
        }
      case (Some(token), Some(googleSubjectId), Some(expiresIn), Some(email), _, _) =>
        // request coming through Apache proxy
        onSuccess {
          getUserInfoFromOidcHeaders(directoryDAO, googleSubjectId, expiresIn, email, OAuth2BearerToken(token)).unsafeToFuture()
        }

      case (_, _, _, _, _, None) =>
        logger.error("requireUserInfo with JWT attempted but Identity Concentrator not configured")
        failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, "Identity Concentrator not configured")))

      case _ =>
        failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Unauthorized, "Missing OIDC headers or valid jwt")))

    }

  def requireCreateUser: Directive1[CreateWorkbenchUser] =
    (optionalHeaderValueByName(googleSubjectIdHeader) &
      optionalHeaderValueByName(emailHeader) &
      headerValueByName(authorizationHeader) &
      provide(identityConcentratorService)
    ) tflatMap {
      // order of these cases is important: if the authorization header is a valid jwt we must ignore
      // any OIDC headers otherwise we may be vulnerable to a malicious user specifying a valid JWT
      // but different user information in the OIDC headers
      case (_, _, JwtAuthorizationHeader((jwtJson, bearerToken)), Some(icService)) =>
        // JWT issued by Identity Concentrator - already validated by proxy
        onSuccess {
          newCreateWorkbenchUserFromJwt(jwtJson, bearerToken, icService).unsafeToFuture()
        }

      case (Some(googleSubjectId), Some(email), _, _) =>
        // request coming through Apache proxy
        provide(CreateWorkbenchUser(genWorkbenchUserId(System.currentTimeMillis()), GoogleSubjectId(googleSubjectId), WorkbenchEmail(email), None))

      case (_, _, _, None) =>
        logger.error("requireCreateUser with JWT attempted but Identity Concentrator not configured")
        failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.InternalServerError, "Identity Concentrator not configured")))
    }
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

  def getUserInfoFromOidcHeaders(
      directoryDAO: DirectoryDAO,
      googleSubjectId: String,
      expiresIn: String,
      email: String,
      bearerToken: OAuth2BearerToken): IO[UserInfo] =
    Try(expiresIn.toLong).fold(
      t => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"expiresIn $expiresIn can't be converted to Long because  of $t"))),
      expiresInLong => getUserInfo(bearerToken, GoogleSubjectId(googleSubjectId), WorkbenchEmail(email), expiresInLong, directoryDAO)
    )

  def getUserInfo(
      token: OAuth2BearerToken,
      googleSubjectId: GoogleSubjectId,
      email: WorkbenchEmail,
      expiresIn: Long,
      directoryDAO: DirectoryDAO): IO[UserInfo] =
    if (isServiceAccount(email)) {
      // If it's a PET account, we treat it as its owner
      directoryDAO.getUserFromPetServiceAccount(ServiceAccountSubjectId(googleSubjectId.value)).flatMap {
        case Some(pet) => IO.pure(UserInfo(token, pet.id, pet.email, expiresIn.toLong))
        case None => lookUpByGoogleSubjectId(googleSubjectId, directoryDAO).map(uid => UserInfo(token, uid, email, expiresIn))
      }
    } else {
      lookUpByGoogleSubjectId(googleSubjectId, directoryDAO).map(uid => UserInfo(token, uid, email, expiresIn))
    }

  def getUserInfoFromJwt(jwtJson: JsObject, bearerToken: OAuth2BearerToken, directoryDAO: DirectoryDAO, identityConcentratorService: IdentityConcentratorService): IO[UserInfo] = {
    for {
      jwtUserInfo <- parseJwtBearerToken(jwtJson)
      maybeUser <- loadUserMaybeUpdateIdentityConcentratorId(jwtUserInfo, bearerToken, directoryDAO, identityConcentratorService)
      user <- maybeUser match {
        case Some(user) => IO.pure(UserInfo(bearerToken, user.id, user.email, jwtUserInfo.exp - Instant.now().getEpochSecond))
        case None =>
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Identity Concentrator Id ${jwtUserInfo.sub} not found in sam")))
      }
    } yield user
  }

  private def loadUserMaybeUpdateIdentityConcentratorId(jwtUserInfo: JwtUserInfo, bearerToken: OAuth2BearerToken, directoryDAO: DirectoryDAO, identityConcentratorService: IdentityConcentratorService): IO[Option[WorkbenchUser]] = {
    for {
      maybeUser <- directoryDAO.loadUserByIdentityConcentratorId(jwtUserInfo.sub)
      maybeUserAgain <- maybeUser match {
        case None => updateUserIdentityConcentratorId(jwtUserInfo, bearerToken, directoryDAO, identityConcentratorService)
        case _ => IO.pure(maybeUser)
      }
    } yield maybeUserAgain
  }

  private def updateUserIdentityConcentratorId(jwtUserInfo: JwtUserInfo, bearerToken: OAuth2BearerToken, directoryDAO: DirectoryDAO, identityConcentratorService: IdentityConcentratorService): IO[Option[WorkbenchUser]] = {
    for {
      googleIdentities <- identityConcentratorService.getGoogleIdentities(bearerToken)
      (googleSubjectId, _) <- singleGoogleIdentity(jwtUserInfo.sub, googleIdentities)
      _ <- directoryDAO.setUserIdentityConcentratorId(googleSubjectId, jwtUserInfo.sub)
      maybeUser <- directoryDAO.loadUserByIdentityConcentratorId(jwtUserInfo.sub)
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

  private def parseJwtBearerToken(jwtJson: JsObject): IO[JwtUserInfo] = {
    for {
      jwtUserInfo <- IO(jwtJson.convertTo[JwtUserInfo]).handleErrorWith {
        case t: DeserializationException =>
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Unauthorized, "could not parse authorization header, jwt missing required fields", t)))
      }
    } yield jwtUserInfo
  }
  private def lookUpByGoogleSubjectId(googleSubjectId: GoogleSubjectId, directoryDAO: DirectoryDAO): IO[WorkbenchUserId] =
    for {
      subject <- directoryDAO.loadSubjectFromGoogleSubjectId(googleSubjectId)
      userInfo <- subject match {
        case Some(uid: WorkbenchUserId) => IO.pure(uid)
        case Some(_) =>
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"subjectId $googleSubjectId is not a WorkbenchUser")))
        case None =>
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"google subject Id $googleSubjectId not found in sam")))
      }
    } yield userInfo

  def newCreateWorkbenchUserFromJwt(jwtJson: JsObject, bearerToken: OAuth2BearerToken, icService: IdentityConcentratorService): IO[CreateWorkbenchUser] =
    for {
      jwtUserInfo <- parseJwtBearerToken(jwtJson)
      googleIdentities <- icService.getGoogleIdentities(bearerToken)
      (googleSubjectId, email) <- singleGoogleIdentity(jwtUserInfo.sub, googleIdentities)
    } yield CreateWorkbenchUser(genWorkbenchUserId(System.currentTimeMillis()), googleSubjectId, email, Option(jwtUserInfo.sub))
}

final case class CreateWorkbenchUser(id: WorkbenchUserId, googleSubjectId: GoogleSubjectId, email: WorkbenchEmail, identityConcentratorId: Option[IdentityConcentratorId])

final case class JwtUserInfo(sub: IdentityConcentratorId, exp: Long)

object JwtAuthorizationHeader extends LazyLogging {
  /**
    * Pattern matcher for JWT authorization header.
    *
    * @param authorizationHeader unprocessed value of the Authorization http header
    * @return None if the header is not a bearer token or is not a parsable JWT.
    * Otherwise it will extract the parsed json of the JWT payload and an OAuth2BearerToken.
    */
  def unapply(authorizationHeader: String): Option[(JsObject, OAuth2BearerToken)] = {
    for {
      bearerToken <- getBearerTokenFromAuthHeader(authorizationHeader)
      json <- parseJwtBearerToken(bearerToken)
    } yield (json, bearerToken)
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
