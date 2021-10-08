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
import org.broadinstitute.dsde.workbench.sam.TestSupport.{eqWorkbenchExceptionErrorReport, genAzureB2CId, samRequestContext}
import org.broadinstitute.dsde.workbench.sam.api.SamRoutes.myExceptionHandler
import org.broadinstitute.dsde.workbench.sam.api.StandardUserInfoDirectives._
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.google.GoogleOpaqueTokenResolver
import org.broadinstitute.dsde.workbench.sam.model.UserStatusInfo
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, UserService}
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import pdi.jwt.JwtSprayJson
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.ExecutionContext
import org.scalatest.flatspec.AnyFlatSpec

class StandardUserInfoDirectivesSpec extends AnyFlatSpec with PropertyBasedTesting with ScalatestRouteTest with ScalaFutures with MockitoSugar {
  def directives: StandardUserInfoDirectives = new StandardUserInfoDirectives {
    override implicit val executionContext: ExecutionContext = null
    override val directoryDAO: DirectoryDAO = new MockDirectoryDAO()
    override val cloudExtensions: CloudExtensions = null
    override val maybeGoogleOpaqueTokenResolver: Option[GoogleOpaqueTokenResolver] = Option(mock[GoogleOpaqueTokenResolver](RETURNS_SMART_NULLS))
    override val userService: UserService = null
  }

  def genAuthorizationHeader(id: Option[IdentityConcentratorId] = None): RawHeader = {
    RawHeader(authorizationHeader, genJwtBearerToken(id).toString())
  }


  private def genJwtBearerToken(id: Option[IdentityConcentratorId]) = {
    import spray.json._
    import org.broadinstitute.dsde.workbench.sam.api.StandardUserInfoDirectives.jwtPayloadFormat
    OAuth2BearerToken(JwtSprayJson.encode(JwtUserInfo(id.getOrElse(genAzureB2CId()), Instant.now().getEpochSecond + 3600).toJson.asJsObject))
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
      val res = getUserInfo(token, googleSubjectId, email, 10L, directoryDAO, samRequestContext).unsafeRunSync()
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
      val res = getUserInfo(token, googleSubjectId, email, 10L, directoryDAO, samRequestContext).unsafeRunSync()
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
      val res = getUserInfo(token, gSid, email, 10L, directoryDAO, samRequestContext).unsafeRunSync()
      res should be (UserInfo(token, uid, email, 10L))
    }
  }

  it should "fail if provided googleSubjectId doesn't exist in sam" in {
    forAll{
      (token: OAuth2BearerToken, email: WorkbenchEmail) =>
        val directoryDAO = new MockDirectoryDAO()
        val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
        val res = getUserInfo(token, googleSubjectId, email, 10L, directoryDAO, samRequestContext).attempt.unsafeRunSync().swap.toOption.get.asInstanceOf[WorkbenchExceptionWithErrorReport]
        Eq[WorkbenchExceptionWithErrorReport].eqv(res, new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"google subject Id $googleSubjectId not found in sam"))) shouldBe(true)
    }
  }

  it should "fail if PET account is not found" in {
    forAll(genOAuth2BearerToken, genPetEmail){
      (token: OAuth2BearerToken, email: WorkbenchEmail) =>
        val directoryDAO = new MockDirectoryDAO()
        val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
        val res = getUserInfo(token, googleSubjectId, email, 10L, directoryDAO, samRequestContext).attempt.unsafeRunSync().swap.toOption.get.asInstanceOf[WorkbenchExceptionWithErrorReport]
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
        val res = getUserInfo(token, gSid, email, 10L, directoryDAO, samRequestContext).attempt.unsafeRunSync().swap.toOption.get.asInstanceOf[WorkbenchExceptionWithErrorReport]

        Eq[WorkbenchExceptionWithErrorReport].eqv(res, new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"subjectId $gSid is not a WorkbenchUser"))) shouldBe(true)
    }
  }

  "getUserInfoFromJwt" should "get user info for valid jwt" in {
    val directoryDAO = new MockDirectoryDAO()
    val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
    val uid = genWorkbenchUserId(System.currentTimeMillis())
    val azureB2CId = TestSupport.genAzureB2CId()
    val email = genNonPetEmail.sample.get
    directoryDAO.createUser(WorkbenchUser(uid, Some(googleSubjectId), email, Option(azureB2CId)), samRequestContext).unsafeRunSync()
    val userInfo = getUserInfoFromJwt(validJwtUserInfo(azureB2CId), OAuth2BearerToken(""), directoryDAO, mock[GoogleOpaqueTokenResolver](RETURNS_SMART_NULLS), samRequestContext).unsafeRunSync()
    assert(userInfo.tokenExpiresIn <= 0)
    assert(userInfo.tokenExpiresIn > -30)
    userInfo.copy(tokenExpiresIn = 0) shouldEqual UserInfo(OAuth2BearerToken(""), uid, email, 0)
  }

  it should "update existing user with azureB2CId" in {
    val directoryDAO = new MockDirectoryDAO()
    val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
    val uid = genWorkbenchUserId(System.currentTimeMillis())
    val azureB2CId = TestSupport.genAzureB2CId()
    val email = genNonPetEmail.sample.get

    directoryDAO.createUser(WorkbenchUser(uid, Some(googleSubjectId), email, None), samRequestContext).unsafeRunSync()

    val googleOpaqueTokenResolver = mock[GoogleOpaqueTokenResolver](RETURNS_SMART_NULLS)
    val bearerToken = OAuth2BearerToken("shhhh, secret")
    when(googleOpaqueTokenResolver.getUserStatusInfo(bearerToken)).thenReturn(IO.pure(Option(UserStatusInfo(googleSubjectId.value, email.value, true))))
    getUserInfoFromJwt(validJwtUserInfo(azureB2CId), bearerToken, directoryDAO, googleOpaqueTokenResolver, samRequestContext).unsafeRunSync()

    directoryDAO.loadUser(uid, samRequestContext).unsafeRunSync().flatMap(_.azureB2CId) shouldBe Some(azureB2CId)
  }

  private def validJwtUserInfo(azureB2CId: AzureB2CId) = JwtUserInfo(azureB2CId, Instant.now().getEpochSecond, None, Seq.empty)

  it should "404 for valid jwt but user does not exist in sam db" in {
    val directoryDAO = new MockDirectoryDAO()
    val t = intercept[WorkbenchExceptionWithErrorReport] {
      val googleOpaqueTokenResolver = mock[GoogleOpaqueTokenResolver](RETURNS_SMART_NULLS)
      when(googleOpaqueTokenResolver.getUserStatusInfo(any[OAuth2BearerToken])).thenReturn(IO.pure(None))
      getUserInfoFromJwt(validJwtUserInfo(TestSupport.genAzureB2CId()), OAuth2BearerToken(""), directoryDAO, googleOpaqueTokenResolver, samRequestContext).unsafeRunSync()
    }
    withClue(t.errorReport) {
      t.errorReport.statusCode shouldBe Some(StatusCodes.NotFound)
    }
  }

  "newCreateWorkbenchUserFromJwt" should "create new CreateWorkbenchUser" in {
    val azureB2CId = TestSupport.genAzureB2CId()
    val email = genNonPetEmail.sample.get

    val googleOpaqueTokenResolver = mock[GoogleOpaqueTokenResolver](RETURNS_SMART_NULLS)
    val bearerToken = OAuth2BearerToken("shhhh, secret")
    when(googleOpaqueTokenResolver.getUserStatusInfo(bearerToken)).thenReturn(IO.pure(None))
    val createUser = newCreateWorkbenchUserFromJwt(validJwtUserInfo(azureB2CId), bearerToken, googleOpaqueTokenResolver).unsafeRunSync()

    createUser.azureB2CId shouldBe Some(azureB2CId)
    createUser.googleSubjectId shouldBe None
    createUser.email shouldBe email
  }

  "requireUserInfo" should "fail if expiresIn is in illegal format" in {
    forAll(genUserInfoHeadersWithInvalidExpiresIn){
      headers: List[RawHeader] =>
        Get("/").withHeaders(headers) ~> handleExceptions(myExceptionHandler){directives.requireUserInfo(samRequestContext)(x => complete(x.toString))} ~> check {
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
      RawHeader(userIdHeader, user.googleSubjectId.get.value),
      RawHeader(accessTokenHeader, accessToken.token),
      RawHeader(authorizationHeader, accessToken.toString()),
      RawHeader(expiresInHeader, (System.currentTimeMillis() + 1000).toString)
    )
    services.directoryDAO.createUser(user, samRequestContext).unsafeRunSync()
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireUserInfo(samRequestContext)(x => complete(x.copy(tokenExpiresIn = 0).toString))} ~> check {
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
      handleExceptions(myExceptionHandler){services.requireUserInfo(samRequestContext)(x => complete(x.copy(tokenExpiresIn = 0).toString))} ~> check {
      assert(rejection.isInstanceOf[MissingHeaderRejection])
    }
  }

  it should "401 when parsable yet invalid jwt and oidc headers given" in {
    val user = genWorkbenchUser.sample.get
    val services = directives
    val accessToken = OAuth2BearerToken(JwtSprayJson.encode("""{"foo": "bar"}"""))
    val headers = List(
      RawHeader(emailHeader, user.email.value),
      RawHeader(userIdHeader, user.googleSubjectId.get.value),
      RawHeader(accessTokenHeader, accessToken.token),
      RawHeader(authorizationHeader, accessToken.toString()),
      RawHeader(expiresInHeader, (System.currentTimeMillis() + 1000).toString)
    )
    services.directoryDAO.createUser(user, samRequestContext).unsafeRunSync()
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireUserInfo(samRequestContext)(x => complete(x.copy(tokenExpiresIn = 0).toString))} ~> check {
      status shouldBe StatusCodes.Unauthorized
    }
  }

  it should "use info from jwt instead of oidc headers" in {
    val services = directives
    val userGood = genWorkbenchUser.sample.get
    val userBad = genWorkbenchUser.sample.get

    val headers = List(
      RawHeader(emailHeader, userBad.email.value),
      RawHeader(userIdHeader, userBad.googleSubjectId.get.value),
      RawHeader(accessTokenHeader, ""),
      genAuthorizationHeader(Option(userGood.identityConcentratorId.get)),
      RawHeader(expiresInHeader, (System.currentTimeMillis() + 1000).toString)
    )

    services.directoryDAO.createUser(userGood, samRequestContext).unsafeRunSync()
    services.directoryDAO.createUser(userBad, samRequestContext).unsafeRunSync()

    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireUserInfo(samRequestContext)(x => complete(x.userId.value))} ~> check {
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
      handleExceptions(myExceptionHandler){services.requireUserInfo(samRequestContext)(x => complete(x.userId.value))} ~> check {
      withClue(responseAs[String]) {
        status shouldBe StatusCodes.OK
      }
      responseAs[String] shouldEqual user.id.value
    }
  }

  it should "fail if required headers are missing" in {
    Get("/") ~> handleExceptions(myExceptionHandler){directives.requireUserInfo(samRequestContext)(x => complete(x.toString))} ~> check {
      rejection shouldBe MissingHeaderRejection(authorizationHeader)
    }
  }

  "requireCreateUser" should "accept request with oidc headers" in {
    val user = genWorkbenchUser.sample.get
    val services = directives
    val accessToken = OAuth2BearerToken("not jwt")
    val headers = List(
      RawHeader(emailHeader, user.email.value),
      RawHeader(userIdHeader, user.googleSubjectId.get.value),
      RawHeader(accessTokenHeader, accessToken.token),
      RawHeader(authorizationHeader, accessToken.toString()),
      RawHeader(expiresInHeader, (System.currentTimeMillis() + 1000).toString)
    )
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireCreateUser(samRequestContext)(x => complete(x.copy(id = WorkbenchUserId("")).toString))} ~> check {
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
      handleExceptions(myExceptionHandler){services.requireCreateUser(samRequestContext)(x => complete(x.toString))} ~> check {
      assert(rejection.isInstanceOf[MissingHeaderRejection])
    }
  }

  it should "401 when parsable yet invalid jwt and oidc headers given" in {
    val user = genWorkbenchUser.sample.get
    val services = directives
    val accessToken = OAuth2BearerToken(JwtSprayJson.encode("""{"foo": "bar"}"""))
    val headers = List(
      RawHeader(emailHeader, user.email.value),
      RawHeader(userIdHeader, user.googleSubjectId.get.value),
      RawHeader(authorizationHeader, accessToken.toString())
    )
    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireCreateUser(samRequestContext)(x => complete(x.toString))} ~> check {
      status shouldBe StatusCodes.Unauthorized
    }
  }

  it should "use info from jwt instead of oidc headers" in {
    val services = directives
    val userGood = genWorkbenchUser.sample.get
    val userBad = genWorkbenchUser.sample.get

    val headers = List(
      RawHeader(emailHeader, userBad.email.value),
      RawHeader(userIdHeader, userBad.googleSubjectId.get.value),
      genAuthorizationHeader(Option(userGood.identityConcentratorId.get))
    )

    services.maybeGoogleOpaqueTokenResolver.map { icService =>
      when(icService.getGoogleIdentities(genJwtBearerToken(userGood.identityConcentratorId))).thenReturn(IO.pure(Seq((userGood.googleSubjectId.get, userGood.email))))
      when(icService.getGoogleIdentities(genJwtBearerToken(userBad.identityConcentratorId))).thenReturn(IO.pure(Seq((userBad.googleSubjectId.get, userBad.email))))
    }

    Get("/").withHeaders(headers) ~>
      handleExceptions(myExceptionHandler){services.requireCreateUser(samRequestContext)(x => complete(x.copy(id = WorkbenchUserId("")).toString))} ~> check {
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

    services.maybeGoogleOpaqueTokenResolver.map { icService =>
      when(icService.getGoogleIdentities(genJwtBearerToken(user.identityConcentratorId))).thenReturn(IO.pure(Seq((user.googleSubjectId.get, user.email))))
    }

    Get("/").withHeaders(authHeader) ~>
      handleExceptions(myExceptionHandler){services.requireCreateUser(samRequestContext)(x => complete(x.identityConcentratorId.toString))} ~> check {
      withClue(responseAs[String]) {
        status shouldBe StatusCodes.OK
      }
      responseAs[String] shouldEqual user.identityConcentratorId.toString
    }
  }

  it should "fail if required headers are missing" in {
    Get("/") ~> handleExceptions(myExceptionHandler){directives.requireCreateUser(samRequestContext)(x => complete(x.toString))} ~> check {
      rejection shouldBe MissingHeaderRejection(authorizationHeader)
    }
  }
}
