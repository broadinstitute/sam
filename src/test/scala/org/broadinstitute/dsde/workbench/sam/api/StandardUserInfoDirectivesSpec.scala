package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{OAuth2BearerToken, RawHeader}
import akka.http.scaladsl.server.Directives.{complete, handleExceptions}
import akka.http.scaladsl.server.MissingHeaderRejection
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import cats.kernel.Eq
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.Generator._
import org.broadinstitute.dsde.workbench.sam.TestSupport.eqWorkbenchExceptionErrorReport
import org.broadinstitute.dsde.workbench.sam.api.SamRoutes.myExceptionHandler
import org.broadinstitute.dsde.workbench.sam.api.StandardUserInfoDirectives._
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.google.{GoogleOpaqueTokenResolver, GoogleTokenInfo, StandardGoogleOpaqueTokenResolver}
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, UserService}
import org.http4s.client.blaze.BlazeClientBuilder
import org.mockito.Mockito._
import org.mockserver.model.{HttpRequest, HttpResponse}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.ExecutionContext

class StandardUserInfoDirectivesSpec extends AnyFlatSpec with PropertyBasedTesting with ScalatestRouteTest with ScalaFutures with MockitoSugar with TestSupport {
  def directives(googleOpaqueTokenResolver: Option[GoogleOpaqueTokenResolver] = None, dirDAO: DirectoryDAO = new MockDirectoryDAO()): StandardUserInfoDirectives = new StandardUserInfoDirectives {
    override implicit val executionContext: ExecutionContext = null
    override val directoryDAO: DirectoryDAO = dirDAO
    override val cloudExtensions: CloudExtensions = null
    override val maybeGoogleOpaqueTokenResolver: Option[GoogleOpaqueTokenResolver] = googleOpaqueTokenResolver
    override val userService: UserService = null
  }

  "getUserInfo" should "be able to get a UserInfo object for regular user" in {
    forAll(minSuccessful(20)) {
      (token: OAuth2BearerToken, email: WorkbenchEmail, externalId: Either[GoogleSubjectId, AzureB2CId]) =>
        val directoryDAO = new MockDirectoryDAO()
        val uid = genWorkbenchUserId(System.currentTimeMillis())
        val oidcHeaders = OIDCHeaders(token, externalId, 10L, email, None)
        directoryDAO.createUser(WorkbenchUser(uid, externalId.left.toOption, email, externalId.toOption), samRequestContext).unsafeRunSync()
        val res = getUserInfo(directoryDAO, None, oidcHeaders, samRequestContext).unsafeRunSync()
        res should be (UserInfo(token, uid, email, 10L))
    }
  }

  it should "be able to get a UserInfo object for service account if it is a PET" in {
    // note that pets can only have google subject ids, not azure b2c ids
    forAll(genServiceAccountSubjectId, genGoogleSubjectId, genOAuth2BearerToken, genPetEmail) {
      (serviceSubjectId: ServiceAccountSubjectId, googleSubjectId: GoogleSubjectId, token: OAuth2BearerToken, email: WorkbenchEmail) =>
      val directoryDAO = new MockDirectoryDAO()
      val uid = genWorkbenchUserId(System.currentTimeMillis())
      directoryDAO.createUser(WorkbenchUser(uid, Option(googleSubjectId), email, None), samRequestContext).unsafeRunSync()
      directoryDAO.createPetServiceAccount(PetServiceAccount(PetServiceAccountId(uid, GoogleProject("")), ServiceAccount(serviceSubjectId, email, ServiceAccountDisplayName(""))), samRequestContext).unsafeRunSync()
      val oidcHeaders = OIDCHeaders(token, Left(GoogleSubjectId(serviceSubjectId.value)), 10L, email, None)
      val res = getUserInfo(directoryDAO, None, oidcHeaders, samRequestContext).unsafeRunSync()
      res should be (UserInfo(token, uid, email, 10L))
    }
  }

  it should "be able to get a UserInfo object for service account if it is a not PET" in {
    forAll(genServiceAccountSubjectId, genOAuth2BearerToken, genPetEmail) {
      (serviceSubjectId: ServiceAccountSubjectId, token: OAuth2BearerToken, email: WorkbenchEmail) =>
      val directoryDAO = new MockDirectoryDAO()
      val gSid = GoogleSubjectId(serviceSubjectId.value)
      val uid = genWorkbenchUserId(System.currentTimeMillis())
      val oidcHeaders = OIDCHeaders(token, Left(gSid), 10L, email, None)
      directoryDAO.createUser(WorkbenchUser(uid, Some(gSid), email, None), samRequestContext).unsafeRunSync()
      val res = getUserInfo(directoryDAO, None, oidcHeaders, samRequestContext).unsafeRunSync()
      res should be (UserInfo(token, uid, email, 10L))
    }
  }

  it should "fail if provided externalId doesn't exist in sam" in {
    forAll {
      (token: OAuth2BearerToken, email: WorkbenchEmail, externalId: Either[GoogleSubjectId, AzureB2CId]) =>
        val directoryDAO = new MockDirectoryDAO()
        val oidcHeaders = OIDCHeaders(token, externalId, 10L, email, None)
        val res = getUserInfo(directoryDAO, None, oidcHeaders, samRequestContext).attempt.unsafeRunSync().swap.toOption.get.asInstanceOf[WorkbenchExceptionWithErrorReport]
        res.errorReport.statusCode shouldBe Option(StatusCodes.NotFound)
    }
  }

  it should "fail if PET account is not found" in {
    forAll(genOAuth2BearerToken, genPetEmail, genGoogleSubjectId){
      (token: OAuth2BearerToken, email: WorkbenchEmail, googleSubjectId: GoogleSubjectId) =>
        val directoryDAO = new MockDirectoryDAO()
        val oidcHeaders = OIDCHeaders(token, Left(googleSubjectId), 10L, email, None)
        val res = getUserInfo(directoryDAO, None, oidcHeaders, samRequestContext).attempt.unsafeRunSync().swap.toOption.get.asInstanceOf[WorkbenchExceptionWithErrorReport]
        res.errorReport.statusCode shouldBe Option(StatusCodes.NotFound)
    }
  }

  it should "fail if a regular user email is provided, but subjectId is not for a regular user" in {
    forAll(genServiceAccountSubjectId, genOAuth2BearerToken, genNonPetEmail){
      (serviceSubjectId: ServiceAccountSubjectId, token: OAuth2BearerToken, email: WorkbenchEmail) =>
        val directoryDAO = new MockDirectoryDAO()
        val gSid = GoogleSubjectId(serviceSubjectId.value)
        val oidcHeaders = OIDCHeaders(token, Left(gSid), 10L, email, None)
        val uid = genWorkbenchUserId(System.currentTimeMillis())
        directoryDAO.createPetServiceAccount(PetServiceAccount(PetServiceAccountId(uid, GoogleProject("")), ServiceAccount(serviceSubjectId, email, ServiceAccountDisplayName(""))), samRequestContext).unsafeRunSync()
        val res = getUserInfo(directoryDAO, None, oidcHeaders, samRequestContext).attempt.unsafeRunSync().swap.toOption.get.asInstanceOf[WorkbenchExceptionWithErrorReport]

        Eq[WorkbenchExceptionWithErrorReport].eqv(res, new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"subjectId $gSid is not a WorkbenchUser"))) shouldBe(true)
    }
  }

  it should "fail if azureB2CId does not exist and idp token is not for current user" in {
    forAll(genGoogleSubjectId, genAzureB2CId, genOAuth2BearerToken, genPetEmail) {
      (googleSubjectId: GoogleSubjectId, azureB2CId: AzureB2CId, token: OAuth2BearerToken, email: WorkbenchEmail) =>
        val directoryDAO = new MockDirectoryDAO()
        val googleOpaqueTokenResolver: GoogleOpaqueTokenResolver = mock[GoogleOpaqueTokenResolver](RETURNS_SMART_NULLS)
        val idpToken = OAuth2BearerToken("opaque_token")
        val uid = genWorkbenchUserId(System.currentTimeMillis())
        when(googleOpaqueTokenResolver.getWorkbenchUser(idpToken, samRequestContext)).thenReturn(IO.pure(None))
        val oidcHeaders = OIDCHeaders(token, Right(azureB2CId), 10L, email, Option(idpToken))
        val workbenchUser = WorkbenchUser(uid, Option(googleSubjectId), email, None)
        directoryDAO.createUser(workbenchUser, samRequestContext).unsafeRunSync()
        val res = getUserInfo(directoryDAO, Option(googleOpaqueTokenResolver), oidcHeaders, samRequestContext).attempt.unsafeRunSync().swap.toOption.get.asInstanceOf[WorkbenchExceptionWithErrorReport]
        res.errorReport.statusCode shouldBe Option(StatusCodes.NotFound)
    }
  }

  it should "update existing user with azureB2CId" in {
    forAll(genGoogleSubjectId, genAzureB2CId, genOAuth2BearerToken, genPetEmail) {
      (googleSubjectId: GoogleSubjectId, azureB2CId: AzureB2CId, token: OAuth2BearerToken, email: WorkbenchEmail) =>
        val directoryDAO = new MockDirectoryDAO()
        val googleOpaqueTokenResolver: GoogleOpaqueTokenResolver = mock[GoogleOpaqueTokenResolver](RETURNS_SMART_NULLS)
        val idpToken = OAuth2BearerToken("opaque_token")
        val uid = genWorkbenchUserId(System.currentTimeMillis())
        when(googleOpaqueTokenResolver.getWorkbenchUser(idpToken, samRequestContext)).thenReturn(IO.pure(Option(GoogleTokenInfo(Option(uid), googleSubjectId))))
        val oidcHeaders = OIDCHeaders(token, Right(azureB2CId), 10L, email, Option(idpToken))
        val workbenchUser = WorkbenchUser(uid, Option(googleSubjectId), email, None)
        directoryDAO.createUser(workbenchUser, samRequestContext).unsafeRunSync()
        val res = getUserInfo(directoryDAO, Option(googleOpaqueTokenResolver), oidcHeaders, samRequestContext).unsafeRunSync()
        res should be (UserInfo(token, uid, email, 10L))
        directoryDAO.loadUser(uid, samRequestContext).unsafeRunSync() shouldBe Option(workbenchUser.copy(azureB2CId = Option(azureB2CId)))
    }
  }

  "requireUserInfo" should "fail if expiresIn is in illegal format" in {
    forAll(genUserInfoHeadersWithInvalidExpiresIn) {
      headers: List[RawHeader] =>
        Get("/").withHeaders(headers) ~> handleExceptions(myExceptionHandler){directives().requireUserInfo(samRequestContext)(x => complete(x.toString))} ~> check {
          val res = responseAs[String]
          status shouldBe StatusCodes.BadRequest
          responseAs[String].contains(s"expiresIn ${headers.find(_.name == expiresInHeader).get.value} can't be converted to Long") shouldBe(true)
        }
    }
  }

  it should "accept request with oidc headers" in forAll(genExternalId, genNonPetEmail, genOAuth2BearerToken, minSuccessful(20)) { (externalId, email, accessToken) =>
    val services = directives()
    val expiresIn = System.currentTimeMillis() + 1000
    val headers = createRequiredHeaders(externalId, email, accessToken, expiresInString = expiresIn.toString)
    val user = services.directoryDAO.createUser(WorkbenchUser(genWorkbenchUserId(System.currentTimeMillis()), externalId.left.toOption, email = email, azureB2CId = externalId.toOption), samRequestContext).unsafeRunSync()
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireUserInfo(samRequestContext)(x => complete(x.toString))} ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] shouldEqual UserInfo(accessToken, user.id, user.email, expiresIn).toString
    }
  }

  it should "fail if required headers are missing" in {
    Get("/") ~> handleExceptions(myExceptionHandler){directives().requireUserInfo(samRequestContext)(x => complete(x.toString))} ~> check {
      rejection shouldBe MissingHeaderRejection(accessTokenHeader)
    }
  }

  it should "populate google if access token matches existing user" in forAll(genAzureB2CId, genNonPetEmail, genOAuth2BearerToken, genGoogleSubjectId) { (azureB2CId, email, accessToken, googleSubjectId) =>
    val idpToken = "opaque_token"
    val tokenInfoResponse = HttpResponse.response(s"""{"sub":"$googleSubjectId"}""")
    createServicesWithGoogleTokenResolver(idpToken, tokenInfoResponse).use { services =>
      IO {
        val expiresIn = System.currentTimeMillis() + 1000
        val headers = createRequiredHeaders(Right(azureB2CId), email, accessToken, Option(idpToken), expiresInString = expiresIn.toString)
        val existingUser = services.directoryDAO.createUser(WorkbenchUser(genWorkbenchUserId(System.currentTimeMillis()), Option(googleSubjectId), email, None), samRequestContext).unsafeRunSync()
        Get("/").withHeaders(headers) ~>
          handleExceptions(myExceptionHandler) {
            services.requireUserInfo(samRequestContext)(x => complete(x.toString))
          } ~> check {
          status shouldBe StatusCodes.OK
          responseAs[String] shouldEqual UserInfo(accessToken, existingUser.id, existingUser.email, expiresIn).toString
          services.directoryDAO.loadUser(existingUser.id, samRequestContext).unsafeRunSync() shouldEqual Some(existingUser.copy(azureB2CId = Option(azureB2CId)))
        }
      }
    }.unsafeRunSync()
  }

  it should "pass if idp access token is not valid for google" in forAll(genAzureB2CId, genNonPetEmail, genOAuth2BearerToken) { (azureB2CId, email, accessToken) =>
    val idpToken = "opaque_token"
    val tokenInfoResponse = HttpResponse.response().withStatusCode(StatusCodes.BadRequest.intValue)
    createServicesWithGoogleTokenResolver(idpToken, tokenInfoResponse).use { services =>
      IO {
        services.directoryDAO.createUser(WorkbenchUser(genWorkbenchUserId(System.currentTimeMillis()), None, email, Option(azureB2CId)), samRequestContext).unsafeRunSync()
        val headers = createRequiredHeaders(Right(azureB2CId), email, accessToken, Option(idpToken))
        Get("/").withHeaders(headers) ~>
          handleExceptions(myExceptionHandler) {
            services.requireUserInfo(samRequestContext)(x => complete(x.toString))
          } ~> check {
          status shouldBe StatusCodes.OK
        }
      }
    }.unsafeRunSync()
  }

  "requireCreateUser" should "accept request with oidc headers" in forAll(genExternalId, genNonPetEmail, genOAuth2BearerToken, minSuccessful(20)) { (externalId, email, accessToken) =>
    val services = directives()
    val headers = createRequiredHeaders(externalId, email, accessToken)
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireCreateUser(samRequestContext)(x => complete(x.copy(id = WorkbenchUserId("")).toString))} ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] shouldEqual CreateWorkbenchUser(WorkbenchUserId(""), externalId.left.toOption, email, externalId.toOption).toString
    }
  }

  it should "fail if idp access token matches existing user" in forAll(genAzureB2CId, genNonPetEmail, genOAuth2BearerToken, genGoogleSubjectId) { (azureB2CId, email, accessToken, googleSubjectId) =>
    val idpToken = "opaque_token"
    val tokenInfoResponse = HttpResponse.response(s"""{"sub":"$googleSubjectId"}""")
    createServicesWithGoogleTokenResolver(idpToken, tokenInfoResponse).use { services =>
      IO {
        val headers = createRequiredHeaders(Right(azureB2CId), email, accessToken, Option(idpToken))
        services.directoryDAO.createUser(WorkbenchUser(genWorkbenchUserId(System.currentTimeMillis()), Option(googleSubjectId), email, None), samRequestContext).unsafeRunSync()
        Get("/").withHeaders(headers) ~>
          handleExceptions(myExceptionHandler) {
            services.requireCreateUser(samRequestContext)(x => complete(x.copy(id = WorkbenchUserId("")).toString))
          } ~> check {
          status shouldBe StatusCodes.Conflict
        }
      }
    }.unsafeRunSync()
  }

  private def createServicesWithGoogleTokenResolver(idpToken: String, tokenInfoResponse: HttpResponse) = {
    val servicesResource = for {
      httpClient <- BlazeClientBuilder.apply[IO](ExecutionContext.global).resource
      mockServer <- mockServerResource
    } yield {
      mockServer.when(HttpRequest.request().withQueryStringParameter("access_token", idpToken)).respond(tokenInfoResponse)

      val directoryDAO = new MockDirectoryDAO()
      val standardGoogleOpaqueTokenResolver = new StandardGoogleOpaqueTokenResolver(directoryDAO, s"http://localhost:${mockServer.getPort}", httpClient)
      directives(Option(standardGoogleOpaqueTokenResolver), directoryDAO)
    }
    servicesResource
  }

  it should "populate google id if idp access token does not match an existing user" in forAll(genAzureB2CId, genNonPetEmail, genOAuth2BearerToken, genGoogleSubjectId) { (azureB2CId, email, accessToken, googleSubjectId) =>
    val idpToken = "opaque_token"
    val tokenInfoResponse = HttpResponse.response(s"""{"sub":"$googleSubjectId"}""")
    createServicesWithGoogleTokenResolver(idpToken, tokenInfoResponse).use { services =>
      IO {
        val headers = createRequiredHeaders(Right(azureB2CId), email, accessToken, Option(idpToken))
        Get("/").withHeaders(headers) ~>
          handleExceptions(myExceptionHandler){services.requireCreateUser(samRequestContext)(x => complete(x.copy(id = WorkbenchUserId("")).toString))} ~> check {
          status shouldBe StatusCodes.OK
          responseAs[String] shouldEqual CreateWorkbenchUser(WorkbenchUserId(""), Option(googleSubjectId), email, Option(azureB2CId)).toString
        }
      }
    }.unsafeRunSync()
  }

  it should "pass if idp access token is not valid for google" in forAll(genAzureB2CId, genNonPetEmail, genOAuth2BearerToken) { (azureB2CId, email, accessToken) =>
    val idpToken = "opaque_token"
    val tokenInfoResponse = HttpResponse.response().withStatusCode(StatusCodes.BadRequest.intValue)
    createServicesWithGoogleTokenResolver(idpToken, tokenInfoResponse).use { services =>
      IO {
        val headers = createRequiredHeaders(Right(azureB2CId), email, accessToken, Option(idpToken))
        Get("/").withHeaders(headers) ~>
          handleExceptions(myExceptionHandler) {
            services.requireCreateUser(samRequestContext)(x => complete(x.copy(id = WorkbenchUserId("")).toString))
          } ~> check {
          status shouldBe StatusCodes.OK
          responseAs[String] shouldEqual CreateWorkbenchUser(WorkbenchUserId(""), None, email, Option(azureB2CId)).toString
        }
      }
    }.unsafeRunSync()
  }

  it should "fail if required headers are missing" in {
    Get("/") ~> handleExceptions(myExceptionHandler){directives().requireCreateUser(samRequestContext)(x => complete(x.toString))} ~> check {
      rejection shouldBe MissingHeaderRejection(accessTokenHeader)
    }
  }

  private def createRequiredHeaders(externalId: Either[GoogleSubjectId, AzureB2CId], email: WorkbenchEmail, accessToken: OAuth2BearerToken, idpToken: Option[String] = None, expiresInString: String = (System.currentTimeMillis() + 1000).toString): List[RawHeader] = {
    List(
      RawHeader(emailHeader, email.value),
      RawHeader(userIdHeader, externalId.fold(_.value, _.value)),
      RawHeader(accessTokenHeader, accessToken.token),
      RawHeader(expiresInHeader, expiresInString)
    ) ++ idpToken.map(RawHeader(idpAccessTokenHeader, _))
  }

}
