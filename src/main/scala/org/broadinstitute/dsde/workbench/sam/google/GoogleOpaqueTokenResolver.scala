package org.broadinstitute.dsde.workbench.sam.google

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.effect.{ContextShift, IO}
import com.typesafe.scalalogging.LazyLogging
import io.circe.Decoder
import org.broadinstitute.dsde.workbench.model.{GoogleSubjectId, WorkbenchException, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.util.{OpenCensusIOUtils, SamRequestContext}
import org.http4s.Method._
import org.http4s._
import org.http4s.circe.jsonOf
import org.http4s.client.Client
import org.http4s.client.dsl.io._
import org.http4s.headers.Accept

/**
  * Converts an opaque google access token into a GoogleTokenInfo
  */
trait GoogleOpaqueTokenResolver {
  /**
    * @return there are 3 return scenarios,
    *         1) token is not a valid google token => None
    *         2) token is valid and matches a user or pet => Some(GoogleTokenInfo(Some(userId), googleSubjectId))
    *         3) token is valid but does not match a user or pet => Some(GoogleTokenInfo(None, googleSubjectId))
    */
  def getGoogleTokenInfo(accessToken: OAuth2BearerToken, samRequestContext: SamRequestContext): IO[Option[GoogleTokenInfo]]
}

/**
  * Calls the google token info api then looks up the google id in the sam db.
  *
  * @param googleTokenInfoUrl
  * @param httpClient
  * @param contextShift
  */
class StandardGoogleOpaqueTokenResolver(directoryDAO: DirectoryDAO, googleTokenInfoUrl: String, httpClient: Client[IO])(implicit contextShift: ContextShift[IO]) extends GoogleOpaqueTokenResolver with LazyLogging {
  implicit val tokenInfoResponseDecoder: Decoder[TokenInfoResponse] = Decoder.forProduct1("sub") {
    sub: String => TokenInfoResponse(GoogleSubjectId(sub))
  }
  implicit val tokenInfoResponseEntityDecoder: EntityDecoder[IO, TokenInfoResponse] = jsonOf[IO, TokenInfoResponse]

  override def getGoogleTokenInfo(accessToken: OAuth2BearerToken, samRequestContext: SamRequestContext): IO[Option[GoogleTokenInfo]] = {
    for {
      request <- GET(
        Uri.unsafeFromString(s"$googleTokenInfoUrl?access_token=${accessToken.token}&token_type_hint=access_token"),
        Accept(MediaType.application.json)
      )
      maybeTokenInfoResponse <- callGoogleTokenInfo(request, samRequestContext)
      maybeWorkbenchUser <- lookupUser(maybeTokenInfoResponse, samRequestContext)
    } yield maybeWorkbenchUser
  }

  private def callGoogleTokenInfo(request: Request[IO], samRequestContext: SamRequestContext) =
    OpenCensusIOUtils.traceIOWithContext("callGoogleTokenInfo", samRequestContext) { _ =>
      httpClient.run(request).use { response: Response[IO] =>
        response.status match {
          case Status.Ok => response.as[TokenInfoResponse].map(Option.apply)
          case _ => IO.pure(None)
        }
      }
    }

  private def lookupUser(maybeTokenInfoResponse: Option[TokenInfoResponse], samRequestContext: SamRequestContext): IO[Option[GoogleTokenInfo]] = {
    maybeTokenInfoResponse.map {
      case TokenInfoResponse(googleSubjectId) =>
        directoryDAO.loadSubjectFromGoogleSubjectId(googleSubjectId, samRequestContext).map {
          case Some(userId: WorkbenchUserId) => Option(GoogleTokenInfo(Option(userId), googleSubjectId))
          case Some(unexpected) => throw new WorkbenchException(s"unexpected workbench identity [$unexpected] associated to access token")
          case None => Option(GoogleTokenInfo(None, googleSubjectId))
        }
    }.getOrElse(IO.none)
  }
}

case class TokenInfoResponse(sub: GoogleSubjectId)

/**
  * Result from authenticating opaque google token and looking up resulting google subject id in sam
  * @param userId Some if the google subject id represents an existing user, None otherwise
  * @param googleSubjectId
  */
case class GoogleTokenInfo(userId: Option[WorkbenchUserId], googleSubjectId: GoogleSubjectId)