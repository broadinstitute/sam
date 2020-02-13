package org.broadinstitute.dsde.workbench.sam.identityConcentrator

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.effect.{ContextShift, IO}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.IdentityConcentratorId
import org.broadinstitute.dsde.workbench.model.google.ServiceAccount
import org.broadinstitute.dsde.workbench.sam.identityConcentrator.IdentityConcentratorModel.{OpenIdConfiguration, UserInfo}
import org.http4s.{AuthScheme, Credentials, MediaType}
import org.http4s.Method._
import org.http4s.client.Client
import org.http4s.client.dsl.io._
import org.http4s.headers.{Accept, Authorization}

trait IdentityConcentratorApi {
  def getUserInfo(accessToken: OAuth2BearerToken): IO[UserInfo]
  def linkServiceAccount(accessToken: OAuth2BearerToken, serviceAccount: ServiceAccount): IO[Unit]
  def enableUser(userId: IdentityConcentratorId): IO[Unit]
  def disableUser(userId: IdentityConcentratorId): IO[Unit]
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

  override def linkServiceAccount(accessToken: OAuth2BearerToken, serviceAccount: ServiceAccount): IO[Unit] = ???
  override def enableUser(userId: IdentityConcentratorId): IO[Unit] = ???
  override def disableUser(userId: IdentityConcentratorId): IO[Unit] = ???
}