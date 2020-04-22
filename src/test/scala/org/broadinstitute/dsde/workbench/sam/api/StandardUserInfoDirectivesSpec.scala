package org.broadinstitute.dsde.workbench.sam
package api

import java.time.Instant

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
import org.broadinstitute.dsde.workbench.sam.TestSupport.{eqWorkbenchExceptionErrorReport, genIdentityConcentratorId}
import org.broadinstitute.dsde.workbench.sam.api.SamRoutes.myExceptionHandler
import org.broadinstitute.dsde.workbench.sam.api.StandardUserInfoDirectives._
import org.broadinstitute.dsde.workbench.sam.directory.{DirectoryDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.identityConcentrator.IdentityConcentratorService
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.scalatest.FlatSpec
import pdi.jwt.JwtSprayJson
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.ExecutionContext

class StandardUserInfoDirectivesSpec extends FlatSpec with PropertyBasedTesting with ScalatestRouteTest with ScalaFutures with MockitoSugar {
  def directives: StandardUserInfoDirectives = new StandardUserInfoDirectives {
    override implicit val executionContext: ExecutionContext = null
    override val directoryDAO: DirectoryDAO = new MockDirectoryDAO()
    override val cloudExtensions: CloudExtensions = null
    override val identityConcentratorService: Option[IdentityConcentratorService] = Option(mock[IdentityConcentratorService])
  }

  def genAuthorizationHeader(id: Option[IdentityConcentratorId] = None): RawHeader = {
    RawHeader(authorizationHeader, genJwtBearerToken(id).toString())
  }


  private def genJwtBearerToken(id: Option[IdentityConcentratorId]) = {
    import spray.json._
    import org.broadinstitute.dsde.workbench.sam.api.StandardUserInfoDirectives.jwtPayloadFormat
    OAuth2BearerToken(JwtSprayJson.encode(JwtUserInfo(id.getOrElse(genIdentityConcentratorId()), Instant.now().getEpochSecond + 3600).toJson.asJsObject))
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
      directoryDAO.createUser(WorkbenchUser(uid, Some(googleSubjectId), email, None), samRequestContext).unsafeRunSync()
      val res = getUserInfo(token, googleSubjectId, email, 10L, directoryDAO).unsafeRunSync()
      res should be (UserInfo(token, uid, email, 10L))
    }
  }

  it should "be able to get a UserInfo object for service account if it is a PET" in {
    forAll(genServiceAccountSubjectId, genGoogleSubjectId, genOAuth2BearerToken, genPetEmail){
      (serviceSubjectId: ServiceAccountSubjectId, googleSubjectId: GoogleSubjectId, token: OAuth2BearerToken, email: WorkbenchEmail) =>
      val directoryDAO = new MockDirectoryDAO()
      val uid = genWorkbenchUserId(System.currentTimeMillis())
      directoryDAO.createUser(WorkbenchUser(uid, Some(googleSubjectId), email, None), samRequestContext).unsafeRunSync()
      directoryDAO.createPetServiceAccount(PetServiceAccount(PetServiceAccountId(uid, GoogleProject("")), ServiceAccount(serviceSubjectId, email, ServiceAccountDisplayName(""))), samRequestContext).unsafeRunSync()
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
      directoryDAO.createUser(WorkbenchUser(uid, Some(gSid), email, None), samRequestContext).unsafeRunSync()
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
        directoryDAO.createPetServiceAccount(PetServiceAccount(PetServiceAccountId(uid, GoogleProject("")), ServiceAccount(serviceSubjectId, email, ServiceAccountDisplayName(""))), samRequestContext).unsafeRunSync()
        val res = getUserInfo(token, gSid, email, 10L, directoryDAO).attempt.unsafeRunSync().swap.toOption.get.asInstanceOf[WorkbenchExceptionWithErrorReport]

        Eq[WorkbenchExceptionWithErrorReport].eqv(res, new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"subjectId $gSid is not a WorkbenchUser"))) shouldBe(true)
    }
  }

  "getUserInfoFromJwt" should "get user info for valid jwt" in {
    val directoryDAO = new MockDirectoryDAO()
    val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
    val uid = genWorkbenchUserId(System.currentTimeMillis())
    val identityConcentratorId = TestSupport.genIdentityConcentratorId()
    val email = genNonPetEmail.sample.get
    directoryDAO.createUser(WorkbenchUser(uid, Some(googleSubjectId), email, Option(identityConcentratorId)), samRequestContext).unsafeRunSync()
    val userInfo = getUserInfoFromJwt(validJwtUserInfo(identityConcentratorId), OAuth2BearerToken(""), directoryDAO, mock[IdentityConcentratorService]).unsafeRunSync()
    assert(userInfo.tokenExpiresIn <= 0)
    assert(userInfo.tokenExpiresIn > -30)
    userInfo.copy(tokenExpiresIn = 0) shouldEqual UserInfo(OAuth2BearerToken(""), uid, email, 0)
  }

  it should "update existing user with ic id" in {
    val directoryDAO = new MockDirectoryDAO()
    val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
    val uid = genWorkbenchUserId(System.currentTimeMillis())
    val identityConcentratorId = TestSupport.genIdentityConcentratorId()
    val email = genNonPetEmail.sample.get

    directoryDAO.createUser(WorkbenchUser(uid, Some(googleSubjectId), email, None), samRequestContext).unsafeRunSync()

    val icService = mock[IdentityConcentratorService]
    val bearerToken = OAuth2BearerToken("shhhh, secret")
    when(icService.getGoogleIdentities(bearerToken)).thenReturn(IO.pure(Seq((googleSubjectId, email))))
    getUserInfoFromJwt(validJwtUserInfo(identityConcentratorId), bearerToken, directoryDAO, icService).unsafeRunSync()

    directoryDAO.loadUser(uid, samRequestContext).unsafeRunSync().flatMap(_.identityConcentratorId) shouldBe Some(identityConcentratorId)
  }

  it should "500 when user is linked to more than 1 google account" in {
    val directoryDAO = new MockDirectoryDAO()
    val identityConcentratorId = TestSupport.genIdentityConcentratorId()
    val icService = mock[IdentityConcentratorService]
    val bearerToken = OAuth2BearerToken("shhhh, secret")

    when(icService.getGoogleIdentities(bearerToken)).thenReturn(IO.pure(Seq(
      (GoogleSubjectId(genRandom(System.currentTimeMillis())), genNonPetEmail.sample.get),
      (GoogleSubjectId(genRandom(System.currentTimeMillis())), genNonPetEmail.sample.get))))

    val expectedError = intercept[WorkbenchExceptionWithErrorReport] {
      getUserInfoFromJwt(validJwtUserInfo(identityConcentratorId), bearerToken, directoryDAO, icService).unsafeRunSync()
    }

    expectedError.errorReport.statusCode shouldBe Some(StatusCodes.InternalServerError)
  }

  private def validJwtUserInfo(identityConcentratorId: IdentityConcentratorId) = JwtUserInfo(identityConcentratorId, Instant.now().getEpochSecond)

  it should "404 for valid jwt but user does not exist in sam db" in {
    val directoryDAO = new MockDirectoryDAO()
    val t = intercept[WorkbenchExceptionWithErrorReport] {
      val identityConcentratorService = mock[IdentityConcentratorService]
      when(identityConcentratorService.getGoogleIdentities(any[OAuth2BearerToken])).thenReturn(IO.pure(Seq((GoogleSubjectId(""), WorkbenchEmail("")))))
      getUserInfoFromJwt(validJwtUserInfo(TestSupport.genIdentityConcentratorId()), OAuth2BearerToken(""), directoryDAO, identityConcentratorService).unsafeRunSync()
    }
    withClue(t.errorReport) {
      t.errorReport.statusCode shouldBe Some(StatusCodes.NotFound)
    }
  }

  it should "500 for valid jwt but no linked accounts in ic" in {
    val directoryDAO = new MockDirectoryDAO()
    val t = intercept[WorkbenchExceptionWithErrorReport] {
      val identityConcentratorService = mock[IdentityConcentratorService]
      when(identityConcentratorService.getGoogleIdentities(any[OAuth2BearerToken])).thenReturn(IO.pure(Seq.empty))
      getUserInfoFromJwt(validJwtUserInfo(TestSupport.genIdentityConcentratorId()), OAuth2BearerToken(""), directoryDAO, identityConcentratorService).unsafeRunSync()
    }
    withClue(t.errorReport) {
      t.errorReport.statusCode shouldBe Some(StatusCodes.InternalServerError)
    }
  }

  "newCreateWorkbenchUserFromJwt" should "create new CreateWorkbenchUser" in {
    val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
    val identityConcentratorId = TestSupport.genIdentityConcentratorId()
    val email = genNonPetEmail.sample.get

    val icService = mock[IdentityConcentratorService]
    val bearerToken = OAuth2BearerToken("shhhh, secret")
    when(icService.getGoogleIdentities(bearerToken)).thenReturn(IO.pure(Seq((googleSubjectId, email))))
    val createUser = newCreateWorkbenchUserFromJwt(validJwtUserInfo(identityConcentratorId), bearerToken, icService).unsafeRunSync()

    createUser.identityConcentratorId shouldBe Some(identityConcentratorId)
    createUser.googleSubjectId shouldBe googleSubjectId
    createUser.email shouldBe email
  }

  it should "500 for 0 google accounts" in {
    val identityConcentratorId = TestSupport.genIdentityConcentratorId()

    val icService = mock[IdentityConcentratorService]
    val bearerToken = OAuth2BearerToken("shhhh, secret")
    when(icService.getGoogleIdentities(bearerToken)).thenReturn(IO.pure(Seq.empty))
    val t = intercept[WorkbenchExceptionWithErrorReport] {
      newCreateWorkbenchUserFromJwt(validJwtUserInfo(identityConcentratorId), bearerToken, icService).unsafeRunSync()
    }

    t.errorReport.statusCode shouldBe Some(StatusCodes.InternalServerError)
  }

  it should "500 for more than 1 google account" in {
    val identityConcentratorId = TestSupport.genIdentityConcentratorId()

    val icService = mock[IdentityConcentratorService]
    val bearerToken = OAuth2BearerToken("shhhh, secret")
    when(icService.getGoogleIdentities(bearerToken)).thenReturn(IO.pure(Seq(
      (GoogleSubjectId(genRandom(System.currentTimeMillis())), genNonPetEmail.sample.get),
      (GoogleSubjectId(genRandom(System.currentTimeMillis())), genNonPetEmail.sample.get))))

    val t = intercept[WorkbenchExceptionWithErrorReport] {
      newCreateWorkbenchUserFromJwt(validJwtUserInfo(identityConcentratorId), bearerToken, icService).unsafeRunSync()
    }

    t.errorReport.statusCode shouldBe Some(StatusCodes.InternalServerError)

  }

  "requireUserInfo" should "fail if expiresIn is in illegal format" in {
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
    val user = genWorkbenchUser.sample.get
    val services = directives
    val accessToken = OAuth2BearerToken("not jwt")
    val headers = List(
      RawHeader(emailHeader, user.email.value),
      RawHeader(googleSubjectIdHeader, user.googleSubjectId.get.value),
      RawHeader(accessTokenHeader, accessToken.token),
      RawHeader(authorizationHeader, accessToken.toString()),
      RawHeader(expiresInHeader, (System.currentTimeMillis() + 1000).toString)
    )
    services.directoryDAO.createUser(user, samRequestContext).unsafeRunSync()
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireUserInfo(x => complete(x.copy(tokenExpiresIn = 0).toString))} ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] shouldEqual UserInfo(accessToken, user.id, user.email, 0).toString
    }
  }

  it should "MissingHeaderRejection when not jwt but no oidc headers given" in {
    val services = directives
    val accessToken = OAuth2BearerToken("not jwt")
    val headers = List(
      RawHeader(authorizationHeader, accessToken.toString())
    )

    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireUserInfo(x => complete(x.copy(tokenExpiresIn = 0).toString))} ~> check {
      assert(rejection.isInstanceOf[MissingHeaderRejection])
    }
  }

  it should "401 when parsable yet invalid jwt and oidc headers given" in {
    val user = genWorkbenchUser.sample.get
    val services = directives
    val accessToken = OAuth2BearerToken(JwtSprayJson.encode("""{"foo": "bar"}"""))
    val headers = List(
      RawHeader(emailHeader, user.email.value),
      RawHeader(googleSubjectIdHeader, user.googleSubjectId.get.value),
      RawHeader(accessTokenHeader, accessToken.token),
      RawHeader(authorizationHeader, accessToken.toString()),
      RawHeader(expiresInHeader, (System.currentTimeMillis() + 1000).toString)
    )
    services.directoryDAO.createUser(user, samRequestContext).unsafeRunSync()
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireUserInfo(x => complete(x.copy(tokenExpiresIn = 0).toString))} ~> check {
      status shouldBe StatusCodes.Unauthorized
    }
  }

  it should "use info from jwt instead of oidc headers" in {
    val services = directives
    val userGood = genWorkbenchUser.sample.get
    val userBad = genWorkbenchUser.sample.get

    val headers = List(
      RawHeader(emailHeader, userBad.email.value),
      RawHeader(googleSubjectIdHeader, userBad.googleSubjectId.get.value),
      RawHeader(accessTokenHeader, ""),
      genAuthorizationHeader(Option(userGood.identityConcentratorId.get)),
      RawHeader(expiresInHeader, (System.currentTimeMillis() + 1000).toString)
    )

    services.directoryDAO.createUser(userGood, samRequestContext).unsafeRunSync()
    services.directoryDAO.createUser(userBad, samRequestContext).unsafeRunSync()

    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireUserInfo(x => complete(x.userId.value))} ~> check {
      withClue(responseAs[String]) {
        status shouldBe StatusCodes.OK
      }
      responseAs[String] shouldEqual userGood.id.value
    }
  }

  it should "accept request with only jwt header" in {
    val user = genWorkbenchUser.sample.get
    val services = directives
    val authHeader = genAuthorizationHeader(user.identityConcentratorId)
    services.directoryDAO.createUser(user, samRequestContext).unsafeRunSync()
    Get("/").withHeaders(authHeader) ~>
      handleExceptions(myExceptionHandler){services.requireUserInfo(x => complete(x.userId.value))} ~> check {
      withClue(responseAs[String]) {
        status shouldBe StatusCodes.OK
      }
      responseAs[String] shouldEqual user.id.value
    }
  }

  it should "fail if required headers are missing" in {
    Get("/") ~> handleExceptions(myExceptionHandler){directives.requireUserInfo(x => complete(x.toString))} ~> check {
      rejection shouldBe MissingHeaderRejection(authorizationHeader)
    }
  }

  "requireCreateUser" should "accept request with oidc headers" in {
    val user = genWorkbenchUser.sample.get
    val services = directives
    val accessToken = OAuth2BearerToken("not jwt")
    val headers = List(
      RawHeader(emailHeader, user.email.value),
      RawHeader(googleSubjectIdHeader, user.googleSubjectId.get.value),
      RawHeader(accessTokenHeader, accessToken.token),
      RawHeader(authorizationHeader, accessToken.toString()),
      RawHeader(expiresInHeader, (System.currentTimeMillis() + 1000).toString)
    )
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireCreateUser(x => complete(x.copy(id = WorkbenchUserId("")).toString))} ~> check {
      status shouldBe StatusCodes.OK
      responseAs[String] shouldEqual CreateWorkbenchUser(WorkbenchUserId(""), user.googleSubjectId.get, user.email, None).toString
    }
  }

  it should "MissingHeaderRejection when not jwt but no oidc headers given" in {
    val services = directives
    val accessToken = OAuth2BearerToken("not jwt")
    val headers = List(
      RawHeader(authorizationHeader, accessToken.toString())
    )

    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireCreateUser(x => complete(x.toString))} ~> check {
      assert(rejection.isInstanceOf[MissingHeaderRejection])
    }
  }

  it should "401 when parsable yet invalid jwt and oidc headers given" in {
    val user = genWorkbenchUser.sample.get
    val services = directives
    val accessToken = OAuth2BearerToken(JwtSprayJson.encode("""{"foo": "bar"}"""))
    val headers = List(
      RawHeader(emailHeader, user.email.value),
      RawHeader(googleSubjectIdHeader, user.googleSubjectId.get.value),
      RawHeader(authorizationHeader, accessToken.toString())
    )
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireCreateUser(x => complete(x.toString))} ~> check {
      status shouldBe StatusCodes.Unauthorized
    }
  }

  it should "use info from jwt instead of oidc headers" in {
    val services = directives
    val userGood = genWorkbenchUser.sample.get
    val userBad = genWorkbenchUser.sample.get

    val headers = List(
      RawHeader(emailHeader, userBad.email.value),
      RawHeader(googleSubjectIdHeader, userBad.googleSubjectId.get.value),
      genAuthorizationHeader(Option(userGood.identityConcentratorId.get))
    )

    services.identityConcentratorService.map { icService =>
      when(icService.getGoogleIdentities(genJwtBearerToken(userGood.identityConcentratorId))).thenReturn(IO.pure(Seq((userGood.googleSubjectId.get, userGood.email))))
      when(icService.getGoogleIdentities(genJwtBearerToken(userBad.identityConcentratorId))).thenReturn(IO.pure(Seq((userBad.googleSubjectId.get, userBad.email))))
    }

    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireCreateUser(x => complete(x.copy(id = WorkbenchUserId("")).toString))} ~> check {
      withClue(responseAs[String]) {
        status shouldBe StatusCodes.OK
      }
      responseAs[String] shouldEqual CreateWorkbenchUser(WorkbenchUserId(""), userGood.googleSubjectId.get, userGood.email, userGood.identityConcentratorId).toString
    }
  }

  it should "accept request with only jwt header" in {
    val user = genWorkbenchUser.sample.get
    val services = directives
    val authHeader = genAuthorizationHeader(user.identityConcentratorId)

    services.identityConcentratorService.map { icService =>
      when(icService.getGoogleIdentities(genJwtBearerToken(user.identityConcentratorId))).thenReturn(IO.pure(Seq((user.googleSubjectId.get, user.email))))
    }

    Get("/").withHeaders(authHeader) ~>
      handleExceptions(myExceptionHandler){services.requireCreateUser(x => complete(x.identityConcentratorId.toString))} ~> check {
      withClue(responseAs[String]) {
        status shouldBe StatusCodes.OK
      }
      responseAs[String] shouldEqual user.identityConcentratorId.toString
    }
  }

  it should "fail if required headers are missing" in {
    Get("/") ~> handleExceptions(myExceptionHandler){directives.requireCreateUser(x => complete(x.toString))} ~> check {
      rejection shouldBe MissingHeaderRejection(authorizationHeader)
    }
  }
}
