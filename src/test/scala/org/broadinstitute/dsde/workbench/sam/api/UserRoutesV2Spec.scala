package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.dataAccess.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.broadinstitute.dsde.workbench.sam.service.{NoExtensions, StatusService, TosService, UserService}

/** Created by mtalbott on 8/8/18.
  */
class UserRoutesV2Spec extends UserRoutesSpecHelper {
  def withSARoutes[T](testCode: (TestSamRoutes, TestSamRoutes) => T): T = {
    val directoryDAO = new MockDirectoryDAO()

    val tosService = new TosService(directoryDAO, TestSupport.tosConfig)
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

  "POST /register/user/v2/self" should "create user" in withDefaultRoutes { samRoutes =>
    Post("/register/user/v2/self") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userSubjectId.value.length shouldBe 21
      res.userInfo.userEmail shouldBe defaultUserEmail
      res.enabled shouldBe TestSupport.enabledMapNoTosAccepted
    }

    Post("/register/user/v2/self") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }

  "GET /register/user/v2/self/info" should "get the status of an enabled user" in withDefaultRoutes { samRoutes =>
    Get("/register/user/v2/self/info") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
    val (user, samDep, routes) = createTestUser(tosAccepted = true)
    Get("/register/user/v2/self/info") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatusInfo] shouldEqual UserStatusInfo(user.id.value, user.email.value, true, true)
    }
  }

  "GET /register/user/v2/self/diagnostics" should "get the diagnostic info for an enabled user" in withDefaultRoutes { samRoutes =>
    val googleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
    Get("/register/user/v2/self/diagnostics") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
    val (user, samDep, routes) = createTestUser(tosAccepted = true)

    Get("/register/user/v2/self/diagnostics") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatusDiagnostics] shouldEqual UserStatusDiagnostics(true, true, true, true, true)
    }
  }

  it should "get user's diagnostics after accepting the tos" in {
    val (user, _, routes) = createTestUser(tosAccepted = true)

    Get("/register/user/v2/self/diagnostics") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val res = responseAs[UserStatusDiagnostics]
      res.tosAccepted shouldBe true
    }
  }

  "GET /register/user/v2/self/termsOfServiceDetails" should "get the user's Terms of Service details" in {
    val (_, _, routes) = createTestUser(tosAccepted = true)

    Get("/register/user/v2/self/termsOfServiceDetails") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val details = responseAs[TermsOfServiceDetails]
      details.isEnabled should be(true)
      details.isGracePeriodEnabled should be(TestSupport.tosConfig.isGracePeriodEnabled)
      details.userAcceptedVersion should not be empty
      details.currentVersion should be(details.userAcceptedVersion.get)
    }
  }

  "GET /register/user/v2/self/termsOfServiceDetails" should "get the user's Terms of Service Adherence Status" in {
    val (user, _, routes) = createTestUser(tosAccepted = true)

    Get("/register/user/v2/self/termsOfServiceAdherenceStatus") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val adherenceStatus = responseAs[TermsOfServiceAdherenceStatus]
      adherenceStatus.userId shouldBe user.id
      adherenceStatus.acceptedTosAllowsUsage shouldBe true
      adherenceStatus.userHasAcceptedLatestTos shouldBe true
    }
  }
}
