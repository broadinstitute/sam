package org.broadinstitute.dsde.workbench.sam.google

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.effect.{ContextShift, IO}
import com.typesafe.scalalogging.LazyLogging
import io.circe.Decoder
import org.broadinstitute.dsde.workbench.model.{GoogleSubjectId, WorkbenchEmail, WorkbenchUser, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.util.{OpenCensusIOUtils, SamRequestContext}
import org.http4s.Method._
import org.http4s._
import org.http4s.circe.jsonOf
import org.http4s.client.Client
import org.http4s.client.dsl.io._
import org.http4s.headers.Accept

/**
  * Converts an opaque google access token into a UserStatusInfo
  */
trait GoogleOpaqueTokenResolver {
  def getWorkbenchUser(accessToken: OAuth2BearerToken, samRequestContext: SamRequestContext): IO[Option[WorkbenchUser]]
}

/**
  * This implementation loops back through the proxy to call the /register/user/v2/self/info endpoint. The proxy has
  * the logic to resolve the opaque token.
  *
  * @param googleTokenInfoUrl
  * @param httpClient
  * @param contextShift
  */
class StandardGoogleOpaqueTokenResolver(directoryDAO: DirectoryDAO, googleTokenInfoUrl: String, httpClient: Client[IO])(implicit contextShift: ContextShift[IO]) extends GoogleOpaqueTokenResolver with LazyLogging {
  implicit val tokenInfoResponseDecoder: Decoder[TokenInfoResponse] = Decoder.forProduct2("sub", "email") {
    (sub: String, email: String) => TokenInfoResponse(GoogleSubjectId(sub), WorkbenchEmail(email)) }
  implicit val tokenInfoResponseEntityDecoder: EntityDecoder[IO, TokenInfoResponse] = jsonOf[IO, TokenInfoResponse]

  override def getWorkbenchUser(accessToken: OAuth2BearerToken, samRequestContext: SamRequestContext): IO[Option[WorkbenchUser]] = {
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

  private def lookupUser(maybeTokenInfoResponse: Option[TokenInfoResponse], samRequestContext: SamRequestContext): IO[Option[WorkbenchUser]] = {
    maybeTokenInfoResponse.map {
      case TokenInfoResponse(googleSubjectId, email) =>
        directoryDAO.loadSubjectFromGoogleSubjectId(googleSubjectId, samRequestContext).map(_.flatMap {
          case userId: WorkbenchUserId => Option(WorkbenchUser(userId, Option(googleSubjectId), email, None))
          case _ => None
        })
    }.getOrElse(IO.none)
  }
}

case class TokenInfoResponse(sub: GoogleSubjectId, email: WorkbenchEmail)