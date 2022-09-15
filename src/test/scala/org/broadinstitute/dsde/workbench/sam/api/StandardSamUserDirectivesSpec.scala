package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{OAuth2BearerToken, RawHeader}
import akka.http.scaladsl.server.Directives.{complete, handleExceptions}
import akka.http.scaladsl.server.MissingHeaderRejection
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.Generator._
import org.broadinstitute.dsde.workbench.sam.TestSupport.googleServicesConfig
import org.broadinstitute.dsde.workbench.sam.api.SamRoutes.myExceptionHandler
import org.broadinstitute.dsde.workbench.sam.api.StandardSamUserDirectives._
import org.broadinstitute.dsde.workbench.sam.config.TermsOfServiceConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, TosService, UserService}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.ExecutionContext

class StandardSamUserDirectivesSpec extends AnyFlatSpec with PropertyBasedTesting with ScalatestRouteTest with ScalaFutures with MockitoSugar with TestSupport {
  def directives(dirDAO: DirectoryDAO = new MockDirectoryDAO(), tosConfig: TermsOfServiceConfig = TestSupport.tosConfig): StandardSamUserDirectives = new StandardSamUserDirectives {
    override implicit val executionContext: ExecutionContext = null
    override val directoryDAO: DirectoryDAO = dirDAO
    override val cloudExtensions: CloudExtensions = null
    override val termsOfServiceConfig: TermsOfServiceConfig = null
    override val userService: UserService = null
    override val tosService: TosService = new TosService(dirDAO, googleServicesConfig.appsDomain, tosConfig)
  }

  "getSamUser" should "be able to get a SamUser object for regular user" in {
    forAll(minSuccessful(20)) {
      (token: OAuth2BearerToken, email: WorkbenchEmail, externalId: Either[GoogleSubjectId, AzureB2CId]) =>
        val directoryDAO = new MockDirectoryDAO()
        val user = Generator.genWorkbenchUserGoogle.sample.get.copy(googleSubjectId = externalId.left.toOption, azureB2CId = externalId.toOption)
        val oidcHeaders = OIDCHeaders(token, externalId, email, None)
        directoryDAO.createUser(user, samRequestContext).unsafeRunSync()
        val res = getSamUser(oidcHeaders, directoryDAO, samRequestContext).unsafeRunSync()
        res should be (user)
    }
  }

  it should "be able to get a SamUser object for service account if it is a PET" in {
    // note that pets can only have google subject ids, not azure b2c ids
    forAll(genServiceAccountSubjectId, genWorkbenchUserGoogle, genOAuth2BearerToken, genServiceAccountEmail) {
      (serviceSubjectId: ServiceAccountSubjectId, user: SamUser, token: OAuth2BearerToken, email: WorkbenchEmail) =>
        val directoryDAO = new MockDirectoryDAO()
        directoryDAO.createUser(user, samRequestContext).unsafeRunSync()
        directoryDAO.createPetServiceAccount(PetServiceAccount(PetServiceAccountId(user.id, GoogleProject("")), ServiceAccount(serviceSubjectId, email, ServiceAccountDisplayName(""))), samRequestContext).unsafeRunSync()
        val oidcHeaders = OIDCHeaders(token, Left(GoogleSubjectId(serviceSubjectId.value)), email, None)
        val res = getSamUser(oidcHeaders, directoryDAO, samRequestContext).unsafeRunSync()
        res should be (user)
    }
  }

  it should "be able to get a SamUser object for service account if it is a not PET" in {
    forAll(genWorkbenchUserServiceAccount, genOAuth2BearerToken, genWorkbenchUserId) {
      (serviceAccountUser: SamUser, token: OAuth2BearerToken, uid: WorkbenchUserId) =>
        val directoryDAO = new MockDirectoryDAO()
        val oidcHeaders = OIDCHeaders(token, Left(serviceAccountUser.googleSubjectId.get), serviceAccountUser.email, None)
        directoryDAO.createUser(serviceAccountUser, samRequestContext).unsafeRunSync()
        val res = getSamUser(oidcHeaders, directoryDAO, samRequestContext).unsafeRunSync()
        res should be (serviceAccountUser)
    }
  }

  it should "fail if provided externalId doesn't exist in sam" in {
    forAll {
      (token: OAuth2BearerToken, email: WorkbenchEmail, externalId: Either[GoogleSubjectId, AzureB2CId]) =>
        val directoryDAO = new MockDirectoryDAO()
        val oidcHeaders = OIDCHeaders(token, externalId, email, None)
        val res = intercept[WorkbenchExceptionWithErrorReport] {
          getSamUser(oidcHeaders, directoryDAO, samRequestContext).unsafeRunSync()
        }
        res.errorReport.statusCode shouldBe Option(StatusCodes.Forbidden)
    }
  }

  it should "fail if PET account is not found" in {
    forAll(genOAuth2BearerToken, genServiceAccountEmail, genGoogleSubjectId){
      (token: OAuth2BearerToken, email: WorkbenchEmail, googleSubjectId: GoogleSubjectId) =>
        val directoryDAO = new MockDirectoryDAO()
        val oidcHeaders = OIDCHeaders(token, Left(googleSubjectId), email, None)
        val res = intercept[WorkbenchExceptionWithErrorReport] {
          getSamUser(oidcHeaders, directoryDAO, samRequestContext).unsafeRunSync()
        }
        res.errorReport.statusCode shouldBe Option(StatusCodes.Forbidden)
    }
  }

  it should "fail if azureB2CId does not exist and google subject id is not for current user" in {
    forAll(genWorkbenchUserGoogle, genAzureB2CId, genOAuth2BearerToken, genGoogleSubjectId, genServiceAccountEmail) {
      (googleUser: SamUser, azureB2CId: AzureB2CId, token: OAuth2BearerToken, otherGoogleSubjectId: GoogleSubjectId, email: WorkbenchEmail) =>
        val directoryDAO = new MockDirectoryDAO()
        val oidcHeaders = OIDCHeaders(token, Right(azureB2CId), email, Option(otherGoogleSubjectId))
        directoryDAO.createUser(googleUser, samRequestContext).unsafeRunSync()
        val res = intercept[WorkbenchExceptionWithErrorReport] {
          getSamUser(oidcHeaders, directoryDAO, samRequestContext).unsafeRunSync()
        }
        res.errorReport.statusCode shouldBe Option(StatusCodes.Forbidden)
    }
  }

  it should "update existing user with azureB2CId" in {
    forAll(genWorkbenchUserGoogle, genAzureB2CId, genOAuth2BearerToken, genServiceAccountEmail) {
      (workbenchUser: SamUser, azureB2CId: AzureB2CId, token: OAuth2BearerToken, email: WorkbenchEmail) =>
        val directoryDAO = new MockDirectoryDAO()
        val oidcHeaders = OIDCHeaders(token, Right(azureB2CId), email, workbenchUser.googleSubjectId)
        directoryDAO.createUser(workbenchUser, samRequestContext).unsafeRunSync()
        val res = getSamUser(oidcHeaders, directoryDAO, samRequestContext).unsafeRunSync()
        val expectedUser = workbenchUser.copy(azureB2CId = Option(azureB2CId))
        res should be (expectedUser)
        directoryDAO.loadUser(workbenchUser.id, samRequestContext).unsafeRunSync() shouldBe Option(expectedUser)
    }
  }

  "withActiveUser" should "accept request with oidc headers" in forAll(genExternalId, genNonPetEmail, genOAuth2BearerToken, genWorkbenchUserId, minSuccessful(20)) { (externalId, email, accessToken, userId) =>
    val services = directives()
    val headers = createRequiredHeaders(externalId, email, accessToken)
    val user = services.directoryDAO.createUser(SamUser(userId, externalId.left.toOption, email = email, azureB2CId = externalId.toOption, true, None), samRequestContext).unsafeRunSync()
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.withActiveUser(samRequestContext)(x => complete(x.toString))} ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] shouldEqual user.toString
    }
  }

  it should "fail if user is disabled" in forAll(genWorkbenchUserAzure, genOAuth2BearerToken) { (user, token) =>
    val services = directives()
    val headers = createRequiredHeaders(Right(user.azureB2CId.get), user.email, token)
    services.directoryDAO.createUser(user.copy(enabled = false), samRequestContext).unsafeRunSync()
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.withActiveUser(samRequestContext)(_ => complete(""))} ~> check {
      status shouldBe StatusCodes.Unauthorized
    }
  }

  it should "fail if user has rejected terms of service" in forAll(genWorkbenchUserAzure, genOAuth2BearerToken) { (user, token) =>
    val services = directives(tosConfig = TestSupport.tosConfig.copy(enabled = true))
    val headers = createRequiredHeaders(Right(user.azureB2CId.get), user.email, token)
    services.directoryDAO.createUser(user.copy(enabled = true), samRequestContext).unsafeRunSync()
    services.tosService.rejectTosStatus(user.id, samRequestContext).unsafeRunSync()
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.withActiveUser(samRequestContext)(_ => complete(""))} ~> check {
      status shouldBe StatusCodes.Unauthorized
    }
  }

  it should "pass if user has rejected terms of service within grace period" in forAll(genWorkbenchUserAzure, genOAuth2BearerToken) { (user, token) =>
    val services = directives(tosConfig = TestSupport.tosConfig.copy(enabled = true, isGracePeriodEnabled = true))
    val headers = createRequiredHeaders(Right(user.azureB2CId.get), user.email, token)
    services.directoryDAO.createUser(user.copy(enabled = true), samRequestContext).unsafeRunSync()
    services.tosService.rejectTosStatus(user.id, samRequestContext).unsafeRunSync()
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.withActiveUser(samRequestContext)(_ => complete(""))} ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "pass if service account has rejected terms" in forAll(genWorkbenchUserServiceAccount, genOAuth2BearerToken) { (user, token) =>
    val services = directives(tosConfig = TestSupport.tosConfig.copy(enabled = true))
    val headers = createRequiredHeaders(Left(user.googleSubjectId.get), user.email, token)
    services.directoryDAO.createUser(user.copy(enabled = true), samRequestContext).unsafeRunSync()
    services.tosService.rejectTosStatus(user.id, samRequestContext).unsafeRunSync()
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.withActiveUser(samRequestContext)(_ => complete(""))} ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "pass if user has accepted terms of service" in forAll(genWorkbenchUserAzure, genOAuth2BearerToken) { (user, token) =>
    val services = directives(tosConfig = TestSupport.tosConfig.copy(enabled = true))
    val headers = createRequiredHeaders(Right(user.azureB2CId.get), user.email, token)
    services.directoryDAO.createUser(user.copy(enabled = true), samRequestContext).unsafeRunSync()
    services.tosService.acceptTosStatus(user.id, samRequestContext).unsafeRunSync()
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.withActiveUser(samRequestContext)(_ => complete(""))} ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  it should "fail if required headers are missing" in {
    Get("/") ~> handleExceptions(myExceptionHandler){directives().withActiveUser(samRequestContext)(x => complete(x.toString))} ~> check {
      rejection shouldBe MissingHeaderRejection(accessTokenHeader)
    }
  }

  it should "populate google id if google id from azure matches existing user" in forAll(genAzureB2CId, genWorkbenchUserGoogle, genOAuth2BearerToken) { (azureB2CId, googleUser, accessToken) =>
    val services = directives(new MockDirectoryDAO())
    val headers = createRequiredHeaders(Right(azureB2CId), googleUser.email, accessToken, googleUser.googleSubjectId)
    val existingUser = services.directoryDAO.createUser(googleUser.copy(enabled = true), samRequestContext).unsafeRunSync()
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler) {
        services.withActiveUser(samRequestContext)(x => complete(x.toString))
      } ~> check {
      status shouldBe StatusCodes.OK
      val exptectedUser = existingUser.copy(azureB2CId = Option(azureB2CId))
      responseAs[String] shouldEqual exptectedUser.toString
      services.directoryDAO.loadUser(existingUser.id, samRequestContext).unsafeRunSync() shouldEqual Some(exptectedUser)
    }
  }

  it should "pass if google id from azure does not exist" in forAll(genWorkbenchUserAzure, genOAuth2BearerToken, genGoogleSubjectId) { (azureUser, accessToken, googleSubjectId) =>
    val services = directives(new MockDirectoryDAO())
    services.directoryDAO.createUser(azureUser.copy(enabled = true), samRequestContext).unsafeRunSync()
    val headers = createRequiredHeaders(Right(azureUser.azureB2CId.get), azureUser.email, accessToken, Option(googleSubjectId))
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler) {
        services.withActiveUser(samRequestContext)(x => complete(x.toString))
      } ~> check {
      status shouldBe StatusCodes.OK
    }
  }

  "withNewUser" should "accept request with oidc headers" in forAll(genExternalId, genNonPetEmail, genOAuth2BearerToken, minSuccessful(20)) { (externalId, email, accessToken) =>
    val services = directives()
    val headers = createRequiredHeaders(externalId, email, accessToken)
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.withNewUser(samRequestContext)(user => complete(user.copy(id = WorkbenchUserId("")).toString))} ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] shouldEqual SamUser(WorkbenchUserId(""), externalId.left.toOption, email, externalId.toOption, false, None).toString
    }
  }

  it should "populate google id from azure" in forAll(genAzureB2CId, genNonPetEmail, genOAuth2BearerToken, genGoogleSubjectId) { (azureB2CId, email, accessToken, googleSubjectId) =>
    val services = directives()
    val headers = createRequiredHeaders(Right(azureB2CId), email, accessToken, Option(googleSubjectId))
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler) {
        services.withNewUser(samRequestContext)(x => complete(x.copy(id = WorkbenchUserId("")).toString))
      } ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] shouldEqual SamUser(WorkbenchUserId(""), Option(googleSubjectId), email, Option(azureB2CId), false, None).toString
    }
  }

  it should "fail if required headers are missing" in {
    Get("/") ~> handleExceptions(myExceptionHandler){directives().withNewUser(samRequestContext)(x => complete(x.toString))} ~> check {
      rejection shouldBe MissingHeaderRejection(accessTokenHeader)
    }
  }

  private def createRequiredHeaders(externalId: Either[GoogleSubjectId, AzureB2CId], email: WorkbenchEmail, accessToken: OAuth2BearerToken, googleIdFromAzure: Option[GoogleSubjectId] = None) = {
    List(
      RawHeader(emailHeader, email.value),
      RawHeader(userIdHeader, externalId.fold(_.value, _.value)),
      RawHeader(accessTokenHeader, accessToken.token),
    ) ++ googleIdFromAzure.map(gid => RawHeader(googleIdFromAzureHeader, gid.value))
  }

}
