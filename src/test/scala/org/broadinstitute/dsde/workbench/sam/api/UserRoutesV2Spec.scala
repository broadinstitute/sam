package org.broadinstitute.dsde.workbench.sam
package api


import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._
import org.broadinstitute.dsde.workbench.sam.TestSupport.appConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.{MockDirectoryDAO, MockRegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.{NoExtensions, StatusService, TosService, UserService}
import org.broadinstitute.dsde.workbench.sam.service.UserService._
/**
  * Created by mtalbott on 8/8/18.
  */
class UserRoutesV2Spec extends UserRoutesSpecHelper {
  def withSARoutes[T](testCode: (TestSamRoutes, TestSamRoutes) => T): T = {
    val directoryDAO = new MockDirectoryDAO()
    val registrationDAO = new MockRegistrationDAO()

    val samRoutes = new TestSamRoutes(null, null, new UserService(directoryDAO, NoExtensions, registrationDAO, Seq.empty, new TosService(directoryDAO, appConfig.googleConfig.get.googleServicesConfig.appsDomain)), new StatusService(directoryDAO, registrationDAO, NoExtensions, TestSupport.dbRef), null, UserInfo(OAuth2BearerToken(""), defaultUserId, defaultUserEmail, 0), directoryDAO, NoExtensions)
    val SARoutes = new TestSamRoutes(null, null, new UserService(directoryDAO, NoExtensions, registrationDAO, Seq.empty, new TosService(directoryDAO, appConfig.googleConfig.get.googleServicesConfig.appsDomain)), new StatusService(directoryDAO,registrationDAO, NoExtensions, TestSupport.dbRef), null, UserInfo(OAuth2BearerToken(""), petSAUserId, petSAEmail, 0), directoryDAO, NoExtensions)
    testCode(samRoutes, SARoutes)
  }

  "POST /register/user/v2/self" should "create user" in withDefaultRoutes { samRoutes =>
    Post("/register/user/v2/self") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userSubjectId.value.length shouldBe 21
      res.userInfo.userEmail shouldBe defaultUserEmail
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)
    }

    Post("/register/user/v2/self") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }

  it should "create a user if ToS is enabled and the user specifies the ToS parameter correctly" in withTosEnabledRoutes { samRoutes =>
    val tos = TermsOfServiceAcceptance("app.terra.bio/#terms-of-service")
    Post("/register/user/v2/self", tos) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userSubjectId.value.length shouldBe 21
      res.userInfo.userEmail shouldBe defaultUserEmail
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)
    }

    Post("/register/user/v2/self", tos) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }

  it should "forbid the registration if ToS is enabled and the user doesn't specify a ToS parameter" in withTosEnabledRoutes { samRoutes =>
    Post("/register/user/v2/self") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
      responseAs[ErrorReport].message should startWith("You must accept the Terms of Service in order to register.")
    }
  }

  it should "forbid the registration if ToS is enabled and the user doesn't specify the correct ToS url" in withTosEnabledRoutes { samRoutes =>
    val tos = TermsOfServiceAcceptance("onemillionpats.com")
    Post("/register/user/v2/self", tos) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
      responseAs[ErrorReport].message should startWith("You must accept the Terms of Service in order to register.")
    }
  }

  it should "forbid the registration if ToS is enabled and the user specifies a differently shaped payload" in withTosEnabledRoutes { samRoutes =>
    val badPayload = UserStatusInfo("doesntmatter", "foobar", true)
    Post("/register/user/v2/self", badPayload) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
      responseAs[ErrorReport].message should startWith("You must accept the Terms of Service in order to register.")
    }
  }

  "GET /register/user/v2/self/info" should "get the status of an enabled user" in withDefaultRoutes { samRoutes =>
    val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
    Get("/register/user/v2/self/info") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
    val (user, samDep, routes) = createTestUser(googSubjectId = Some(googleSubjectId))
    Get("/register/user/v2/self/info") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatusInfo] shouldEqual UserStatusInfo(user.id.value, user.email.value, true)
    }
  }

  "GET /register/user/v2/self/diagnostics" should "get the diagnostic info for an enabled user" in withDefaultRoutes { samRoutes =>
    val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
    Get("/register/user/v2/self/diagnostics") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
    val (user, samDep, routes) = createTestUser(googSubjectId = Some(googleSubjectId))

    Get("/register/user/v2/self/diagnostics") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatusDiagnostics] shouldEqual UserStatusDiagnostics(true, true, true)
    }
  }
}
