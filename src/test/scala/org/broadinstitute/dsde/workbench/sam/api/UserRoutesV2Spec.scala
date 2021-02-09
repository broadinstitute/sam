package org.broadinstitute.dsde.workbench.sam
package api


import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.dataAccess.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.{NoExtensions, StatusService, UserService}
import org.broadinstitute.dsde.workbench.sam.service.UserService._
/**
  * Created by mtalbott on 8/8/18.
  */
class UserRoutesV2Spec extends UserRoutesSpecHelper {
  def withSARoutes[T](testCode: (TestSamRoutes, TestSamRoutes) => T): T = {
    val directoryDAO = new MockDirectoryDAO()
    val registrationDAO = new MockDirectoryDAO()

    val samRoutes = new TestSamRoutes(null, null, new UserService(directoryDAO, NoExtensions, registrationDAO, Seq.empty), new StatusService(directoryDAO, NoExtensions, TestSupport.dbRef), null, UserInfo(OAuth2BearerToken(""), defaultUserId, defaultUserEmail, 0), directoryDAO, NoExtensions)
    val SARoutes = new TestSamRoutes(null, null, new UserService(directoryDAO, NoExtensions, registrationDAO, Seq.empty), new StatusService(directoryDAO, NoExtensions, TestSupport.dbRef), null, UserInfo(OAuth2BearerToken(""), petSAUserId, petSAEmail, 0), directoryDAO, NoExtensions)
    testCode(samRoutes, SARoutes)
  }

  "POST /register/user/v2/self" should "create user" in withDefaultRoutes { samRoutes =>
    Post("/register/user/v2/self") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userStatusDetails.userSubjectId.value.length shouldBe 21
      res.userStatusDetails.userEmail shouldBe defaultUserEmail
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)
    }

    Post("/register/user/v2/self") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
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
