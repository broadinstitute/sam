package org.broadinstitute.dsde.workbench.sam.google

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.effect.{ContextShift, IO}
import com.typesafe.scalalogging.LazyLogging
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import org.broadinstitute.dsde.workbench.model.WorkbenchException
import org.broadinstitute.dsde.workbench.sam.model.UserStatusInfo
import org.http4s.Method._
import org.http4s._
import org.http4s.circe.jsonOf
import org.http4s.client.Client
import org.http4s.client.dsl.io._
import org.http4s.headers.{Accept, Authorization}

/**
  * Converts an opaque google access token into a UserStatusInfo
  */
trait GoogleOpaqueTokenResolver {
  def getUserStatusInfo(accessToken: OAuth2BearerToken): IO[Option[UserStatusInfo]]
}

/**
  * This implementation loops back through the proxy to call the /register/user/v2/self/info endpoint. The proxy has
  * the logic to resolve the opaque token.
  *
  * @param samBaseUrl
  * @param httpClient
  * @param contextShift
  */
class StandardGoogleOpaqueTokenResolver(samBaseUrl: String, httpClient: Client[IO])(implicit contextShift: ContextShift[IO]) extends GoogleOpaqueTokenResolver with LazyLogging {
  implicit val userStatusInfoDecoder: Decoder[UserStatusInfo] = deriveDecoder[UserStatusInfo]
  implicit val userStatusInfoEntityDecoder: EntityDecoder[IO, UserStatusInfo] = jsonOf[IO, UserStatusInfo]

  override def getUserStatusInfo(accessToken: OAuth2BearerToken): IO[Option[UserStatusInfo]] = {
    for {
      request <- GET(
        Uri.unsafeFromString(s"$samBaseUrl/register/user/v2/self/info"),
        Authorization(Credentials.Token(AuthScheme.Bearer, accessToken.token)),
        Accept(MediaType.application.json)
      )
      maybeUserStatusInfo <- httpClient.run(request).use { response: Response[IO] =>
        response.status match {
          case Status.Ok => response.as[UserStatusInfo].map(Option.apply)
          case Status.NotFound | Status.Unauthorized => IO.pure(None)
          case _ => IO.raiseError(new WorkbenchException(s"${response.status} error calling ${request.uri}, body: ${response.bodyText.compile.string.unsafeRunSync()}"))
        }
      }
    } yield maybeUserStatusInfo
  }
}