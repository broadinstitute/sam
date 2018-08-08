package org.broadinstitute.dsde.workbench.sam.api


import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.{NoExtensions, StatusService, UserService}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.mockito.MockitoSugar

/**
  * Created by mtalbott on 8/8/18.
  */
class UserRoutesV2Spec extends FlatSpec with Matchers with ScalatestRouteTest with MockitoSugar with TestSupport {

  val defaultUserId = WorkbenchUserId("newuser")
  val defaultUserEmail = WorkbenchEmail("newuser@new.com")
  val petSAUserId = WorkbenchUserId("123")
  val petSAEmail = WorkbenchEmail("pet-newuser@test.iam.gserviceaccount.com")

  def withDefaultRoutes[T](testCode: TestSamRoutes => T): T = {
    val directoryDAO = new MockDirectoryDAO()

    val samRoutes = new TestSamRoutes(null, new UserService(directoryDAO, NoExtensions), new StatusService(directoryDAO, NoExtensions), null, UserInfo(OAuth2BearerToken(""), defaultUserId, defaultUserEmail, 0), directoryDAO)
    testCode(samRoutes)
  }

  def withSARoutes[T](testCode: (TestSamRoutes, TestSamRoutes) => T): T = {
    val directoryDAO = new MockDirectoryDAO()

    val samRoutes = new TestSamRoutes(null, new UserService(directoryDAO, NoExtensions), new StatusService(directoryDAO, NoExtensions), null, UserInfo(OAuth2BearerToken(""), defaultUserId, defaultUserEmail, 0), directoryDAO, NoExtensions)
    val SARoutes = new TestSamRoutes(null, new UserService(directoryDAO, NoExtensions), new StatusService(directoryDAO, NoExtensions), null, UserInfo(OAuth2BearerToken(""), petSAUserId, petSAEmail, 0), directoryDAO, NoExtensions)
    testCode(samRoutes, SARoutes)
  }

  "POST /register/user/v2/self" should "create user" in withDefaultRoutes { samRoutes =>
    Post("/register/user/v2/self") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Post("/register/user/v2/self") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }

  "GET /register/user/v2/self/info" should "get the status of an enabled user" in withDefaultRoutes { samRoutes =>
    Post("/register/user/v2/self") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Get("/register/user/v2/self/info") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatusInfo] shouldEqual UserStatusInfo(UserStatusDetails(defaultUserId, defaultUserEmail), true)
    }
  }

  "GET /register/user/v2/self/diagnostics" should "get the diagnostic info for an enabled user" in withDefaultRoutes { samRoutes =>
    Post("/register/user/v2/self") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Get("/register/user/v2/self/diagnostics") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatusDiagnostics] shouldEqual UserStatusDiagnostics(true, true, true)
    }
  }

  "POST /register/user/v2/self" should "create a user" in withSARoutes{ (samRoutes, SARoutes) =>
    Post("/register/user/v2/self") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Get("/register/user/v2/self/info") ~> SARoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatusInfo] shouldEqual UserStatusInfo(UserStatusDetails(defaultUserId, defaultUserEmail), true)
    }
  }
}
