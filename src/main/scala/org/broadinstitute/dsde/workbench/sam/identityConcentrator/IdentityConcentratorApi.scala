package org.broadinstitute.dsde.workbench.sam.identityConcentrator

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.effect.{ContextShift, IO}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.sam.identityConcentrator.IdentityConcentratorModel.{OpenIdConfiguration, UserInfo}
import org.http4s.{AuthScheme, Credentials, MediaType}
import org.http4s.Method._
import org.http4s.client.Client
import org.http4s.client.dsl.io._
import org.http4s.headers.{Accept, Authorization}

/**
  * Trait encompassing the raw requests to Identity Concentrator. Implementations should be concerned only with
  * marshalling a request, performing that request and unmarshalling the response. Any other logic should be within
  * IdentityConcentratorService. This model allows for easy unit testing.
  */
trait IdentityConcentratorApi {
  def getUserInfo(accessToken: OAuth2BearerToken): IO[UserInfo]
}

class StandardIdentityConcentratorApi(icBaseUrl: String, httpClient: Client[IO])(implicit contextShift: ContextShift[IO]) extends IdentityConcentratorApi with LazyLogging {

  val openIdConfigPath = "/.well-known/openid-configuration"
  lazy val openIdConfig: OpenIdConfiguration = loadOpenIdConfig.unsafeRunSync()

  def loadOpenIdConfig: IO[OpenIdConfiguration] = {
    val openIdConfigUrl = s"${icBaseUrl.stripSuffix("/")}$openIdConfigPath"
    httpClient.expect[OpenIdConfiguration](openIdConfigUrl)
  }

  override def getUserInfo(accessToken: OAuth2BearerToken): IO[UserInfo] = {
    val request = GET(
      openIdConfig.userinfo_endpoint,
      Authorization(Credentials.Token(AuthScheme.Bearer, accessToken.token)),
      Accept(MediaType.application.json)
    )
    httpClient.expect[UserInfo](request)
  }
}