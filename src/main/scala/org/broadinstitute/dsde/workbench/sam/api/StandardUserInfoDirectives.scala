package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives.{headerValueByName, onSuccess, optionalHeaderValueByName}
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.OnSuccessMagnet._
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountSubjectId
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, _}
import org.broadinstitute.dsde.workbench.sam.api.StandardUserInfoDirectives._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.service.UserService._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import pdi.jwt.{JwtOptions, JwtSprayJson}
import spray.json._
import java.time.Instant

trait StandardUserInfoDirectives extends UserInfoDirectives {
  implicit val executionContext: ExecutionContext

  def requireUserInfo: Directive1[UserInfo] =
    (
      optionalHeaderValueByName(accessTokenHeader) &
        optionalHeaderValueByName(googleSubjectIdHeader) &
        optionalHeaderValueByName(expiresInHeader) &
        optionalHeaderValueByName(emailHeader) &
        headerValueByName(authorizationHeader)
    ) tflatMap {
      case (Some(token), Some(googleSubjectId), Some(expiresIn), Some(email), _) =>
        // request coming through Apache proxy
        onSuccess {
          getUserInfoFromOidcHeaders(directoryDAO, googleSubjectId, expiresIn, email, OAuth2BearerToken(token)).unsafeToFuture()
        }
      case (_, _, _, _, authHeader) =>
        // JWT issued by Identity Concentrator - already validated by proxy
        onSuccess {
          getUserInfoFromJwt(authHeader, directoryDAO).unsafeToFuture()
        }
    }

  def requireCreateUser: Directive1[CreateWorkbenchUser] =
    (
      headerValueByName(googleSubjectIdHeader) &
        headerValueByName(emailHeader)
    ) tflatMap {
      case (googleSubjectId, email) => {
        onSuccess(
          Future.successful(CreateWorkbenchUser(genWorkbenchUserId(System.currentTimeMillis()), GoogleSubjectId(googleSubjectId), WorkbenchEmail(email))))
      }
    }
}

object StandardUserInfoDirectives {
  val SAdomain = "\\S+@\\S+\\.iam\\.gserviceaccount\\.com".r
  val accessTokenHeader = "OIDC_access_token"
  val expiresInHeader = "OIDC_CLAIM_expires_in"
  val emailHeader = "OIDC_CLAIM_email"
  val googleSubjectIdHeader = "OIDC_CLAIM_user_id"
  val authorizationHeader = "Authorization"

  def isServiceAccount(email: WorkbenchEmail) =
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

  def getUserInfoFromJwt(authorizationHeader: String, directoryDAO: DirectoryDAO): IO[UserInfo] = {
    for {
      bearerToken <- getBearerTokenFromAuthHeader(authorizationHeader)
      jwtUserInfo <- parseJwtBearerToken(bearerToken)
      maybeUser <- directoryDAO.loadUserByIdentityConcentratorId(jwtUserInfo.sub)
      user <- maybeUser match {
        case Some(user) => IO.pure(UserInfo(bearerToken, user.id, user.email, jwtUserInfo.exp - Instant.now().getEpochSecond))
        case None =>
          // TODO in the next iteration call to IC to find user's google subject id and populate sam_user table
          IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Identity Concentrator Id ${jwtUserInfo.sub} not found in sam")))
      }
    } yield user
  }

  private def getBearerTokenFromAuthHeader(authorizationHeader: String): IO[OAuth2BearerToken] = {
    val bearerPrefix = "bearer "
    // need to be case insensitive when looking for the 'bearer' prefix so can't use stripPrefix
    if (authorizationHeader.toLowerCase.startsWith(bearerPrefix)) {
      IO.pure(OAuth2BearerToken(authorizationHeader.drop(bearerPrefix.size)))
    } else {
      IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Unauthorized, "could not parse authorization header, not a bearer token")))
    }
  }

  private def parseJwtBearerToken(bearerToken: OAuth2BearerToken): IO[JwtUserInfo] = {
    import DefaultJsonProtocol._
    import WorkbenchIdentityJsonSupport.identityConcentratorIdFormat
    implicit val jwtPayloadFormat = jsonFormat2(JwtUserInfo)

    // Assumption: JWT is already validated
    JwtSprayJson.decodeJson(bearerToken.token, JwtOptions(signature = false, expiration = false)).fold[IO[JwtUserInfo]](
      t => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Unauthorized, "could not parse authorization header, invalid jwt", t))),
      parsedJwt => {
        for {
          jwtUserInfo <- IO(parsedJwt.convertTo[JwtUserInfo]).handleErrorWith {
            case t: DeserializationException =>
              IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Unauthorized, "could not parse authorization header, jwt missing required fields", t)))
          }
        } yield jwtUserInfo
      }
    )
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
}

// TODO remove default identityConcentratorId when implementing register user with IC
final case class CreateWorkbenchUser(id: WorkbenchUserId, googleSubjectId: GoogleSubjectId, email: WorkbenchEmail, identityConcentratorId: Option[IdentityConcentratorId] = None)

final case class JwtUserInfo(sub: IdentityConcentratorId, exp: Long)
