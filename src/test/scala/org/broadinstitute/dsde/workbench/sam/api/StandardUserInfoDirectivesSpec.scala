package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{OAuth2BearerToken, RawHeader}
import akka.http.scaladsl.server.Directives.{complete, handleExceptions}
import akka.http.scaladsl.server.MissingHeaderRejection
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.kernel.Eq
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.Generator._
import org.broadinstitute.dsde.workbench.sam.TestSupport.{eqWorkbenchExceptionErrorReport, samRequestContext}
import org.broadinstitute.dsde.workbench.sam.api.SamRoutes.myExceptionHandler
import org.broadinstitute.dsde.workbench.sam.api.StandardUserInfoDirectives._
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.google.GoogleOpaqueTokenResolver
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, UserService}
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.ExecutionContext

class StandardUserInfoDirectivesSpec extends AnyFlatSpec with PropertyBasedTesting with ScalatestRouteTest with ScalaFutures with MockitoSugar {
  def directives: StandardUserInfoDirectives = new StandardUserInfoDirectives {
    override implicit val executionContext: ExecutionContext = null
    override val directoryDAO: DirectoryDAO = new MockDirectoryDAO()
    override val cloudExtensions: CloudExtensions = null
    override val maybeGoogleOpaqueTokenResolver: Option[GoogleOpaqueTokenResolver] = Option(mock[GoogleOpaqueTokenResolver](RETURNS_SMART_NULLS))
    override val userService: UserService = null
  }

  "getUserInfo" should "be able to get a UserInfo object for regular user" in {
    forAll {
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
      val oidcHeaders = OIDCHeaders(token, Left(googleSubjectId), 10L, email, None)
      directoryDAO.createUser(WorkbenchUser(uid, Option(googleSubjectId), email, None), samRequestContext).unsafeRunSync()
      directoryDAO.createPetServiceAccount(PetServiceAccount(PetServiceAccountId(uid, GoogleProject("")), ServiceAccount(serviceSubjectId, email, ServiceAccountDisplayName(""))), samRequestContext).unsafeRunSync()
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

  it should "update existing user with azureB2CId" in pending

  "requireUserInfo" should "fail if expiresIn is in illegal format" in {
    forAll(genUserInfoHeadersWithInvalidExpiresIn) {
      headers: List[RawHeader] =>
        Get("/").withHeaders(headers) ~> handleExceptions(myExceptionHandler){directives.requireUserInfo(samRequestContext)(x => complete(x.toString))} ~> check {
          val res = responseAs[String]
          status shouldBe StatusCodes.BadRequest
          responseAs[String].contains(s"expiresIn ${headers.find(_.name == expiresInHeader).get.value} can't be converted to Long") shouldBe(true)
        }
    }
  }

  it should "accept request with oidc headers" in forAll(genExternalId, genNonPetEmail, genOAuth2BearerToken, minSuccessful(20)) { (externalId, email, accessToken) =>
    val services = directives
    val expiresIn = System.currentTimeMillis() + 1000
    val headers = List(
      RawHeader(emailHeader, email.value),
      RawHeader(userIdHeader, externalId.fold(_.value, _.value)),
      RawHeader(accessTokenHeader, accessToken.token),
      RawHeader(expiresInHeader, expiresIn.toString)
    )
    val user = services.directoryDAO.createUser(WorkbenchUser(genWorkbenchUserId(System.currentTimeMillis()), externalId.left.toOption, email = email, azureB2CId = externalId.toOption), samRequestContext).unsafeRunSync()
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireUserInfo(samRequestContext)(x => complete(x.toString))} ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] shouldEqual UserInfo(accessToken, user.id, user.email, expiresIn).toString
    }
  }

  it should "fail if required headers are missing" in {
    Get("/") ~> handleExceptions(myExceptionHandler){directives.requireUserInfo(samRequestContext)(x => complete(x.toString))} ~> check {
      rejection shouldBe MissingHeaderRejection(accessTokenHeader)
    }
  }

  "requireCreateUser" should "accept request with oidc headers" in forAll(genExternalId, genNonPetEmail, genOAuth2BearerToken, minSuccessful(20)) { (externalId, email, accessToken) =>
    val services = directives
    val headers = List(
      RawHeader(emailHeader, email.value),
      RawHeader(userIdHeader, externalId.fold(_.value, _.value)),
      RawHeader(accessTokenHeader, accessToken.token),
      RawHeader(expiresInHeader, (System.currentTimeMillis() + 1000).toString)
    )
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireCreateUser(samRequestContext)(x => complete(x.copy(id = WorkbenchUserId("")).toString))} ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] shouldEqual CreateWorkbenchUser(WorkbenchUserId(""), externalId.left.toOption, email, externalId.toOption).toString
    }
  }

  it should "fail if idp access token is for an existing user" in pending

  it should "fail if required headers are missing" in {
    Get("/") ~> handleExceptions(myExceptionHandler){directives.requireCreateUser(samRequestContext)(x => complete(x.toString))} ~> check {
      rejection shouldBe MissingHeaderRejection(accessTokenHeader)
    }
  }
}
