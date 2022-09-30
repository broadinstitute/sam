package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.Generator.genNonPetEmail
import org.broadinstitute.dsde.workbench.sam.TestSupport.googleServicesConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.RootPrimitiveJsonSupport.rootBooleanJsonFormat
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.{NoExtensions, StatusService, TosService, UserService}

/** Created by dvoet on 6/7/17.
  */
class UserRoutesV1Spec extends UserRoutesSpecHelper {

  def withSARoutes[T](testCode: (TestSamRoutes, TestSamRoutes) => T): T = {
    val directoryDAO = new MockDirectoryDAO()

    val tosService = new TosService(directoryDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig)
    val samRoutes = new TestSamRoutes(
      null,
      null,
      new UserService(directoryDAO, NoExtensions, Seq.empty, tosService),
      new StatusService(directoryDAO, NoExtensions, TestSupport.dbRef),
      null,
      defaultUser,
      directoryDAO,
      NoExtensions,
      tosService = tosService
    )
    val SARoutes = new TestSamRoutes(
      null,
      null,
      new UserService(directoryDAO, NoExtensions, Seq.empty, tosService),
      new StatusService(directoryDAO, NoExtensions, TestSupport.dbRef),
      null,
      petSAUser,
      directoryDAO,
      NoExtensions,
      tosService = tosService
    )
    testCode(samRoutes, SARoutes)
  }

  "POST /register/user/v1/" should "create user" in withDefaultRoutes { samRoutes =>
    Post("/register/user/v1/") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userSubjectId.value.length shouldBe 21
      res.userInfo.userEmail shouldBe defaultUserEmail
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)
    }

    Post("/register/user/v1/") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }

  it should "create a user and accept the tos when the user specifies the ToS body correctly" in {
    val (user, _, routes) = createTestUser(tosEnabled = true, tosAccepted = false)
    val tos = TermsOfServiceAcceptance("app.terra.bio/#terms-of-service")

    Post("/register/user/v1/termsofservice", tos) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val res = responseAs[UserStatus]
      res.userInfo.userSubjectId shouldBe user.id
      res.userInfo.userEmail shouldBe user.email
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true, "tosAccepted" -> true, "adminEnabled" -> true)
    }
  }

  it should "forbid the registration if ToS is enabled and the user doesn't specify the correct ToS url" in {
    val (_, _, routes) = createTestUser(tosEnabled = true, tosAccepted = false)

    val tos = TermsOfServiceAcceptance("onemillionpats.com")
    Post("/register/user/v1/termsofservice", tos) ~> routes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
      responseAs[ErrorReport].message should startWith("You must accept the Terms of Service in order to register.")
    }
  }

  it should "get user's registration status after accepting the tos" in {
    val (user, _, routes) = createTestUser(tosEnabled = true, tosAccepted = true)

    Get("/register/user/v1") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val res = responseAs[UserStatus]
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true, "tosAccepted" -> true, "adminEnabled" -> true)
    }
  }

  "GET /register/user/v1/termsofservice/status" should "return 404 when ToS is disabled" in {
    val (_, _, routes) = createTestUser(tosEnabled = false)

    Get("/register/user/v1/termsofservice/status") ~> routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "return 200 + false when ToS is enabled but the user hasn't accepted the ToS" in {
    val (user, _, routes) = createTestUser(tosEnabled = true, tosAccepted = false)

    Get("/register/user/v1/termsofservice/status") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val res = responseAs[Boolean]
      res shouldBe false
    }
  }

  it should "return 200 + true when ToS is enabled and the user has accepted the ToS" in {
    val (user, _, routes) = createTestUser(tosEnabled = true, tosAccepted = true)

    Get("/register/user/v1/termsofservice/status") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val res = responseAs[Boolean]
      res shouldBe true
    }
  }

  "POST /api/users/v1/invite/{invitee's email}" should "create user" in {
    val inviteeEmail = genNonPetEmail.sample.get

    val (user, _, routes) = createTestUser() // create a valid user that can invite someone
    Post(s"/api/users/v1/invite/${inviteeEmail}") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatusDetails]
      res.userEmail shouldBe inviteeEmail
    }
  }

  "GET /register/user/v1/" should "get the status of an enabled user" in {
    val (user, samDep, routes) = createTestUser()

    Get("/register/user/v1/") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Get("/register/user/v1?userDetailsOnly=true") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map.empty)
    }
  }

  "GET /api/users/v1/{email}" should "return the subject id, google subject id, and email for a user" in {
    val (user, samDep, routes) = createTestUser()

    Get(s"/api/users/v1/${user.email.value}") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserIdInfo] shouldEqual UserIdInfo(user.id, user.email, user.googleSubjectId)
    }
  }

  it should "return 404 when the user is not registered" in {
    val (user, samDep, routes) = createTestUser()

    Get(s"/api/users/v1/doesntexist@foo.bar") ~> routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }
}
