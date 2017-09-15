package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model.{WorkbenchGroupEmail, WorkbenchGroupName, WorkbenchUserEmail}
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.UserService
import org.scalatest.{FlatSpec, Matchers}

/**
  * Created by dvoet on 6/7/17.
  */
class UserRoutesSpec extends FlatSpec with Matchers with ScalatestRouteTest {
  val defaultUserId = SamUserId("newuser")
  val defaultUserEmail = SamUserEmail("newuser@new.com")
  val adminUserId = SamUserId("adminuser")
  val adminUserEmail = SamUserEmail("adminuser@new.com")

  def withDefaultRoutes[T](testCode: TestSamRoutes => T): T = {
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()

    val samRoutes = new TestSamRoutes(Map.empty, null, new UserService(directoryDAO, googleDirectoryDAO, "dev.test.firecloud.org"), UserInfo("", defaultUserId, defaultUserEmail, 0))
    testCode(samRoutes)
  }

  def setupAdminsGroup(googleDirectoryDAO: MockGoogleDirectoryDAO): Unit = {
    for {
      _ <- googleDirectoryDAO.createGroup(WorkbenchGroupName("fc-admins"), WorkbenchGroupEmail("fc-admins@dev.test.firecloud.org"))
      _ <- googleDirectoryDAO.addMemberToGroup(WorkbenchGroupEmail("fc-admins@dev.test.firecloud.org"), WorkbenchUserEmail(adminUserEmail.value))
    } yield ()
  }

  def withAdminRoutes[T](testCode: (TestSamRoutes, TestSamRoutes) => T): T = {
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()

    setupAdminsGroup(googleDirectoryDAO)

    val samRoutes = new TestSamRoutes(Map.empty, null, new UserService(directoryDAO, googleDirectoryDAO, "dev.test.firecloud.org"), UserInfo("", defaultUserId, defaultUserEmail, 0))
    val adminRoutes = new TestSamRoutes(Map.empty, null, new UserService(directoryDAO, googleDirectoryDAO, "dev.test.firecloud.org"), UserInfo("", adminUserId, adminUserEmail, 0))
    testCode(samRoutes, adminRoutes)
  }

  "UserRoutes" should "create user" in withDefaultRoutes { samRoutes =>
    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[SamUserStatus] shouldEqual SamUserStatus(SamUserInfo(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }

  it should "get the status of an enabled user" in withDefaultRoutes { samRoutes =>
    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[SamUserStatus] shouldEqual SamUserStatus(SamUserInfo(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Get("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUserStatus] shouldEqual SamUserStatus(SamUserInfo(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  it should "get the user status of a user (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[SamUserStatus] shouldEqual SamUserStatus(SamUserInfo(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Get(s"/api/admin/user/$defaultUserId") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUserStatus] shouldEqual SamUserStatus(SamUserInfo(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  it should "disable and then re-enable a user (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[SamUserStatus] shouldEqual SamUserStatus(SamUserInfo(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Put(s"/api/admin/user/$defaultUserId/disable") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUserStatus] shouldEqual SamUserStatus(SamUserInfo(defaultUserId, defaultUserEmail), Map("ldap" -> false, "allUsersGroup" -> true, "google" -> false))
    }

    Put(s"/api/admin/user/$defaultUserId/enable") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUserStatus] shouldEqual SamUserStatus(SamUserInfo(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  it should "delete a user (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[SamUserStatus] shouldEqual SamUserStatus(SamUserInfo(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Delete(s"/api/admin/user/$defaultUserId") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    Get(s"/api/admin/user/$defaultUserId") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "not allow a non-admin to enable or disable a user" in withAdminRoutes { (samRoutes, _) =>
    Put(s"/api/admin/user/$defaultUserId/disable") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }

    Put(s"/api/admin/user/$defaultUserId/enable") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "not allow a non-admin to delete a user" in withAdminRoutes { (samRoutes, _) =>
    Delete(s"/api/admin/user/$defaultUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "not allow a non-admin to get the status of another user" in withAdminRoutes { (samRoutes, _) =>
    Get(s"/api/admin/user/$defaultUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

}
