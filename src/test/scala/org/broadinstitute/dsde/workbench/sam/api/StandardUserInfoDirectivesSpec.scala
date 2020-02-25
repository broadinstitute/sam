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
import org.broadinstitute.dsde.workbench.sam.TestSupport.eqWorkbenchExceptionErrorReport
import org.broadinstitute.dsde.workbench.sam.api.SamRoutes.myExceptionHandler
import org.broadinstitute.dsde.workbench.sam.api.StandardUserInfoDirectives._
import org.broadinstitute.dsde.workbench.sam.directory.{DirectoryDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.scalatest.FlatSpec
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.ExecutionContext

class StandardUserInfoDirectivesSpec extends FlatSpec with PropertyBasedTesting with ScalatestRouteTest with ScalaFutures{
  def directives: StandardUserInfoDirectives = new StandardUserInfoDirectives {
    override implicit val executionContext: ExecutionContext = null
    override val directoryDAO: DirectoryDAO = new MockDirectoryDAO()
    override val cloudExtensions: CloudExtensions = null
  }

  "isServiceAccount" should "be able to tell whether an email is a PET email" in {
    forAll(genPetEmail, genNonPetEmail){
      (petEmail: WorkbenchEmail, nonPetEmail: WorkbenchEmail) =>
        isServiceAccount(petEmail) shouldBe(true)
        isServiceAccount(nonPetEmail) shouldBe(false)
    }
  }

  "getUserInfo" should "be able to get a UserInfo object for regular user" in {
    forAll{
      (token: OAuth2BearerToken, email: WorkbenchEmail) =>
      val directoryDAO = new MockDirectoryDAO()
      val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
      val uid = genWorkbenchUserId(System.currentTimeMillis())
      directoryDAO.createUser(WorkbenchUser(uid, Some(googleSubjectId), email, None)).unsafeRunSync()
      val res = getUserInfo(token, googleSubjectId, email, 10L, directoryDAO).unsafeRunSync()
      res should be (UserInfo(token, uid, email, 10L))
    }
  }

  it should "be able to get a UserInfo object for service account if it is a PET" in {
    forAll(genServiceAccountSubjectId, genGoogleSubjectId, genOAuth2BearerToken, genPetEmail){
      (serviceSubjectId: ServiceAccountSubjectId, googleSubjectId: GoogleSubjectId, token: OAuth2BearerToken, email: WorkbenchEmail) =>
      val directoryDAO = new MockDirectoryDAO()
      val uid = genWorkbenchUserId(System.currentTimeMillis())
      directoryDAO.createUser(WorkbenchUser(uid, Some(googleSubjectId), email, None)).unsafeRunSync()
      directoryDAO.createPetServiceAccount(PetServiceAccount(PetServiceAccountId(uid, GoogleProject("")), ServiceAccount(serviceSubjectId, email, ServiceAccountDisplayName("")))).unsafeRunSync()
      val res = getUserInfo(token, googleSubjectId, email, 10L, directoryDAO).unsafeRunSync()
      res should be (UserInfo(token, uid, email, 10L))
    }
  }

  it should "be able to get a UserInfo object for service account if it is a not PET" in {
    forAll(genServiceAccountSubjectId, genOAuth2BearerToken, genPetEmail){
      (serviceSubjectId: ServiceAccountSubjectId, token: OAuth2BearerToken, email: WorkbenchEmail) =>
      val directoryDAO = new MockDirectoryDAO()
      val gSid = GoogleSubjectId(serviceSubjectId.value)
      val uid = genWorkbenchUserId(System.currentTimeMillis())
      directoryDAO.createUser(WorkbenchUser(uid, Some(gSid), email, None)).unsafeRunSync()
      val res = getUserInfo(token, gSid, email, 10L, directoryDAO).unsafeRunSync()
      res should be (UserInfo(token, uid, email, 10L))
    }
  }

  it should "fail if provided googleSubjectId doesn't exist in sam" in {
    forAll{
      (token: OAuth2BearerToken, email: WorkbenchEmail) =>
        val directoryDAO = new MockDirectoryDAO()
        val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
        val res = getUserInfo(token, googleSubjectId, email, 10L, directoryDAO).attempt.unsafeRunSync().swap.toOption.get.asInstanceOf[WorkbenchExceptionWithErrorReport]
        Eq[WorkbenchExceptionWithErrorReport].eqv(res, new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"google subject Id $googleSubjectId not found in sam"))) shouldBe(true)
    }
  }

  it should "fail if PET account is not found" in {
    forAll(genOAuth2BearerToken, genPetEmail){
      (token: OAuth2BearerToken, email: WorkbenchEmail) =>
        val directoryDAO = new MockDirectoryDAO()
        val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
        val res = getUserInfo(token, googleSubjectId, email, 10L, directoryDAO).attempt.unsafeRunSync().swap.toOption.get.asInstanceOf[WorkbenchExceptionWithErrorReport]
        Eq[WorkbenchExceptionWithErrorReport].eqv(res, new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"google subject Id $googleSubjectId not found in sam"))) shouldBe(true)
    }
  }

  it should "fail if a regular user email is provided, but subjectId is not for a regular user" in {
    forAll(genServiceAccountSubjectId, genOAuth2BearerToken, genNonPetEmail){
      (serviceSubjectId: ServiceAccountSubjectId, token: OAuth2BearerToken, email: WorkbenchEmail) =>
        val directoryDAO = new MockDirectoryDAO()
        val gSid = GoogleSubjectId(serviceSubjectId.value)
        val uid = genWorkbenchUserId(System.currentTimeMillis())
        directoryDAO.createPetServiceAccount(PetServiceAccount(PetServiceAccountId(uid, GoogleProject("")), ServiceAccount(serviceSubjectId, email, ServiceAccountDisplayName("")))).unsafeRunSync()
        val res = getUserInfo(token, gSid, email, 10L, directoryDAO).attempt.unsafeRunSync().swap.toOption.get.asInstanceOf[WorkbenchExceptionWithErrorReport]

        Eq[WorkbenchExceptionWithErrorReport].eqv(res, new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"subjectId $gSid is not a WorkbenchUser"))) shouldBe(true)
    }
  }

  // this JWT was generated by identity concentrator. Using it just to be sure we can parse the real thing
  val validJwtFromIdentityConcentrator = "eyJhbGciOiJSUzI1NiIsImtpZCI6InB1YmxpYzozZWQxZTgwYy1kZTBkLTQwNTctYTQwZi05ZDgxZjRlMzM2OTQiLCJ0eXAiOiJKV1QifQ.eyJhdWQiOlsiOTAzY2ZhZWItNTdkOS00ZWY2LTU2NTktMDQzNzc3OTRlZDY1Il0sImNsaWVudF9pZCI6IjkwM2NmYWViLTU3ZDktNGVmNi01NjU5LTA0Mzc3Nzk0ZWQ2NSIsImV4cCI6MTU4MTYwNTIzMCwiZXh0Ijp7ImlkZW50aXRpZXMiOlsiZHZvZXR0ZXN0QGdtYWlsLmNvbSJdfSwiaWF0IjoxNTgxNjAxNjI5LCJpc3MiOiJodHRwczovL2ljLWRvdC1kb2dlaWN0ZXN0LmFwcHNwb3QuY29tLyIsImp0aSI6Ijc0ZGFkNTFkLTEwZTAtNDk4OC1iOGRlLWI2YTkzYmExODcxMCIsIm5iZiI6MTU4MTYwMTYyOSwic2NwIjpbIm9wZW5pZCIsIm9mZmxpbmUiLCJwcm9maWxlIiwiaWRlbnRpdGllcyIsImdhNGdoX3Bhc3Nwb3J0X3YxIl0sInN1YiI6ImljX2ZiOGMyNGYxMWNkYjQ5MGU4ODE1NzQifQ.B7KUVxofXA_5PBgIN4qEIBl3vhXntgoGKNJ0AE-FzC2X_vLLBSeZ4FpGIjHjFr75XHG0-kPU1wwiz7_wmwsJpwMhybvRyn5f2PjFfzf6HrD3cEkJiz0PnIxkH4Eb3xksOJMhAfZh1ZQlbKXdcc54oDmM3JTyu-YqIQT1J1kBmAqWM3Wy_YX2KtSZHJTQHVxDhFdq9EQosraNJT7wH9lFPPZmNq5YXQVOw7V4lB9UBOFC4sSwnS332xPN_tmzxcobmzEVHpA3UwKI1QfxXQRLxpllavlizN80hJlQgXGIk-GYgFe92JpNbhx2H0ipiUj16e7uKpmwtI3myoXPkQ8wOeVB2ofuLP-FZxojLY4fuUEUA7hg0Gmu6dvV9sz5bVHmxlaIQQl7Pjvz5FlJ7fWWOOTvOLjwMNM546b9Oe_tdovv2cqyQI4f6p6Q0Bk5O7v4-BCd8RWHgWTaA_-GQ0ixn26YUkFUyCilhR4TFJE_0t1Dsyb-wlwc1Op0oTjfilpRlCaOT9AhsMnLWqvtKFhMhIis2_vDCwNSDfNwNXDe-rTCMPqxR42jCfpBOQ8SPSs8YHyLYCGXPTDnsm0zwYg2imM-palKBeJM6RGkQOzBkCA3T4IYuDnxZJekpxGbSluPeruPwc02EjB7Q2qWHw3jnPPSTslwjsw1IieCcxdFmUc"

  "getUserInfoFromJwt" should "get user info for valid jwt" in {
    val directoryDAO = new MockDirectoryDAO()
    val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
    val uid = genWorkbenchUserId(System.currentTimeMillis())
    val someIdentityConcentratorId = Some(IdentityConcentratorId("ic_fb8c24f11cdb490e881574"))
    val email = genNonPetEmail.sample.get
    directoryDAO.createUser(WorkbenchUser(uid, Some(googleSubjectId), email, someIdentityConcentratorId)).unsafeRunSync()
    val userInfo = getUserInfoFromJwt(s"Bearer $validJwtFromIdentityConcentrator", directoryDAO).unsafeRunSync()
    // jwt was generated a while ago so expiresIn should be negative
    assert(userInfo.tokenExpiresIn < 0)
    userInfo.copy(tokenExpiresIn = 0) shouldEqual UserInfo(OAuth2BearerToken(validJwtFromIdentityConcentrator), uid, email, 0)
  }

  it should "404 for valid jwt but user does not exist" in {
    val directoryDAO = new MockDirectoryDAO()
    val t = intercept[WorkbenchExceptionWithErrorReport] {
      getUserInfoFromJwt(s"Bearer $validJwtFromIdentityConcentrator", directoryDAO).unsafeRunSync()
    }
    t.errorReport.statusCode shouldBe Some(StatusCodes.NotFound)
  }

  it should "401 with no exp" in {
    val directoryDAO = new MockDirectoryDAO()
    val t = intercept[WorkbenchExceptionWithErrorReport] {
      getUserInfoFromJwt(s"Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c", directoryDAO).unsafeRunSync()
    }
    t.errorReport.statusCode shouldBe Some(StatusCodes.Unauthorized)
  }

  it should "401 with no sub" in {
    val directoryDAO = new MockDirectoryDAO()
    val t = intercept[WorkbenchExceptionWithErrorReport] {
      getUserInfoFromJwt(s"Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJuYW1lIjoiSm9obiBEb2UiLCJpYXQiOjE1MTYyMzkwMjIsImV4cCI6MTUxNjIzOTAyMn0.yOZC0rjfSopcpJ-d3BWE8-BkoLR_SCqPdJpq8Wn-1Mc", directoryDAO).unsafeRunSync()
    }
    t.errorReport.statusCode shouldBe Some(StatusCodes.Unauthorized)
  }

  it should "401 for invalid jwt" in {
    val directoryDAO = new MockDirectoryDAO()
    val t = intercept[WorkbenchExceptionWithErrorReport] {
      getUserInfoFromJwt(s"Bearer this is invalid", directoryDAO).unsafeRunSync()
    }
    t.errorReport.statusCode shouldBe Some(StatusCodes.Unauthorized)
  }

  "StandardUserInfoDirectives" should "fail if expiresIn is in illegal format" in {
    forAll(genUserInfoHeadersWithInvalidExpiresIn){
      headers: List[RawHeader] =>
        Get("/").withHeaders(headers) ~> handleExceptions(myExceptionHandler){directives.requireUserInfo(x => complete(x.toString))} ~> check {
          val res = responseAs[String]
          status shouldBe StatusCodes.BadRequest
          responseAs[String].contains(s"expiresIn ${headers.find(_.name == expiresInHeader).get.value} can't be converted to Long") shouldBe(true)
        }
    }
  }

  it should "accept request with oidc headers" in {
    val email = genNonPetEmail.sample.get
    val services = directives
    val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
    val uid = genWorkbenchUserId(System.currentTimeMillis())
    val someIdentityConcentratorId = None
    val accessToken = OAuth2BearerToken("not jwt")
    val headers = List(
      RawHeader(emailHeader, email.value),
      RawHeader(googleSubjectIdHeader, googleSubjectId.value),
      RawHeader(accessTokenHeader, accessToken.token),
      RawHeader(authorizationHeader, accessToken.toString()),
      RawHeader(expiresInHeader, (System.currentTimeMillis() + 1000).toString)
    )
    services.directoryDAO.createUser(WorkbenchUser(uid, Some(googleSubjectId), email, someIdentityConcentratorId)).unsafeRunSync()
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireUserInfo(x => complete(x.copy(tokenExpiresIn = 0).toString))} ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] shouldEqual UserInfo(accessToken, uid, email, 0).toString
    }
  }

  it should "accept request with only jwt header" in {
    val services = directives
    val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
    val uid = genWorkbenchUserId(System.currentTimeMillis())
    val someIdentityConcentratorId = Some(IdentityConcentratorId("testuser"))
    val email = genNonPetEmail.sample.get
    val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ0ZXN0dXNlciIsImV4cCI6MTUxNjIzOTAyMn0.ivXtw21QZ57gOgDFDskS6MCWRSs24-uwzT1QrzbvBwE"
    services.directoryDAO.createUser(WorkbenchUser(uid, Some(googleSubjectId), email, someIdentityConcentratorId)).unsafeRunSync()
    Get("/").withHeaders(List(RawHeader(authorizationHeader, s"bearer $jwt"))) ~>
      handleExceptions(myExceptionHandler){services.requireUserInfo(x => complete(x.copy(tokenExpiresIn = 0).toString))} ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] shouldEqual UserInfo(OAuth2BearerToken(jwt), uid, email, 0).toString
    }
  }

  it should "fail if required headers are missing" in {
    Get("/") ~> handleExceptions(myExceptionHandler){directives.requireUserInfo(x => complete(x.toString))} ~> check {
      rejection shouldBe MissingHeaderRejection(authorizationHeader)
    }
  }

  //skipping positive test for StandardUserInfoDirectives since all other routes mixes in StandardUserInfoDirectives relies on success path.
}
