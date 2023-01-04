package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport.googleServicesConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.broadinstitute.dsde.workbench.sam.service.{NoServices, StatusService, TosService, UserService}

/** Created by mtalbott on 8/8/18.
  */
class UserRoutesV2Spec extends UserRoutesSpecHelper {
  def withSARoutes[T](testCode: (TestSamRoutes, TestSamRoutes) => T): T = {
    val directoryDAO = new MockDirectoryDAO()

    val tosService = new TosService(directoryDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig)
    val samRoutes = new TestSamRoutes(
      null,
      null,
      new UserService(directoryDAO, NoServices, Seq.empty, tosService),
      new StatusService(directoryDAO, NoServices, TestSupport.dbRef),
      null,
      defaultUser,
      directoryDAO,
      NoServices,
      tosService = tosService
    )
    val SARoutes = new TestSamRoutes(
      null,
      null,
      new UserService(directoryDAO, NoServices, Seq.empty, tosService),
      new StatusService(directoryDAO, NoServices, TestSupport.dbRef),
      null,
      petSAUser,
      directoryDAO,
      NoServices,
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
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)
    }

    Post("/register/user/v2/self") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }

  "GET /register/user/v2/self/info" should "get the status of an enabled user" in withDefaultRoutes { samRoutes =>
    Get("/register/user/v2/self/info") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
    val (user, samDep, routes) = createTestUser()
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
    val (user, samDep, routes) = createTestUser(tosEnabled = true, tosAccepted = true)

    Get("/register/user/v2/self/diagnostics") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatusDiagnostics] shouldEqual UserStatusDiagnostics(true, true, true, Option(true), true)
    }
  }

  it should "get user's diagnostics after accepting the tos" in {
    val (user, _, routes) = createTestUser(tosEnabled = true, tosAccepted = true)

    Get("/register/user/v2/self/diagnostics") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val res = responseAs[UserStatusDiagnostics]
      res.tosAccepted shouldBe Some(true)
    }
  }
}
