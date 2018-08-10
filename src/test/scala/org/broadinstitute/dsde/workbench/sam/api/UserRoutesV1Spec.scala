package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.{NoExtensions, StatusService, UserService}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Future

/**
  * Created by dvoet on 6/7/17.
  */
class UserRoutesV1Spec extends FlatSpec with Matchers with ScalatestRouteTest with MockitoSugar with TestSupport {
  val defaultUserId = WorkbenchUserId("newuser")
  val defaultUserEmail = WorkbenchEmail("newuser@new.com")
  val adminUserId = WorkbenchUserId("adminuser")
  val adminUserEmail = WorkbenchEmail("adminuser@new.com")
  val petSAUserId = WorkbenchUserId("123")
  val petSAEmail = WorkbenchEmail("pet-newuser@test.iam.gserviceaccount.com")

  def withDefaultRoutes[T](testCode: TestSamRoutes => T): T = {
    val directoryDAO = new MockDirectoryDAO()

    val samRoutes = new TestSamRoutes(null, new UserService(directoryDAO, NoExtensions), new StatusService(directoryDAO, NoExtensions), null, UserInfo(OAuth2BearerToken(""), defaultUserId, defaultUserEmail, 0), directoryDAO)
    testCode(samRoutes)
  }

  def setupAdminsGroup(googleDirectoryDAO: MockGoogleDirectoryDAO): Future[WorkbenchEmail] = {
    val adminGroupEmail = WorkbenchEmail("fc-admins@dev.test.firecloud.org")
    for {
      _ <- googleDirectoryDAO.createGroup(WorkbenchGroupName("fc-admins"), adminGroupEmail)
      _ <- googleDirectoryDAO.addMemberToGroup(adminGroupEmail, WorkbenchEmail(adminUserEmail.value))
    } yield adminGroupEmail
  }

  def withAdminRoutes[T](testCode: (TestSamRoutes, TestSamRoutes) => T): T = {
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()

    val adminGroupEmail = runAndWait(setupAdminsGroup(googleDirectoryDAO))

    val cloudExtensions = new NoExtensions {
      override def isWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = googleDirectoryDAO.isGroupMember(adminGroupEmail, memberEmail)
    }

    val samRoutes = new TestSamRoutes(null, new UserService(directoryDAO, cloudExtensions), new StatusService(directoryDAO, NoExtensions), null, UserInfo(OAuth2BearerToken(""), defaultUserId, defaultUserEmail, 0), directoryDAO, cloudExtensions)
    val adminRoutes = new TestSamRoutes(null, new UserService(directoryDAO, cloudExtensions), new StatusService(directoryDAO, NoExtensions), null, UserInfo(OAuth2BearerToken(""), adminUserId, adminUserEmail, 0), directoryDAO, cloudExtensions)
    testCode(samRoutes, adminRoutes)
  }

  def withSARoutes[T](testCode: (TestSamRoutes, TestSamRoutes) => T): T = {
    val directoryDAO = new MockDirectoryDAO()

    val samRoutes = new TestSamRoutes(null, new UserService(directoryDAO, NoExtensions), new StatusService(directoryDAO, NoExtensions), null, UserInfo(OAuth2BearerToken(""), defaultUserId, defaultUserEmail, 0), directoryDAO, NoExtensions)
    val SARoutes = new TestSamRoutes(null, new UserService(directoryDAO, NoExtensions), new StatusService(directoryDAO, NoExtensions), null, UserInfo(OAuth2BearerToken(""), petSAUserId, petSAEmail, 0), directoryDAO, NoExtensions)
    testCode(samRoutes, SARoutes)
  }

  "POST /register/user/v1/" should "create user" in withDefaultRoutes { samRoutes =>
    Post("/register/user/v1/") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Post("/register/user/v1/") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }

  "GET /register/user/v1/" should "get the status of an enabled user" in withDefaultRoutes { samRoutes =>
    Post("/register/user/v1/") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Get("/register/user/v1/") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Get("/register/user/v1?userDetailsOnly=true") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map.empty)
    }
  }


  "POST /register/user/v1/" should "create a user" in withSARoutes{ (samRoutes, SARoutes) =>
    Post("/register/user/v1/") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Get("/register/user/v1/") ~> SARoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  "GET /admin/user/{userSubjectId}" should "get the user status of a user (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
    Post("/register/user/v1/") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Get(s"/api/admin/user/$defaultUserId") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  it should "not allow a non-admin to get the status of another user" in withAdminRoutes { (samRoutes, _) =>
    Get(s"/api/admin/user/$defaultUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "GET /admin/user/email/{email}" should "get the user status of a user by email (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
    Post("/register/user/v1/") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Get(s"/api/admin/user/email/$defaultUserEmail") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  it should "return 404 for an unknown user by email (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
    Get(s"/api/admin/user/email/XXX${defaultUserEmail}XXX") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "return 404 for an group's email (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
    Get(s"/api/admin/user/email/fc-admins@dev.test.firecloud.org") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "not allow a non-admin to get the status of another user" in withAdminRoutes { (samRoutes, _) =>
    Get(s"/api/admin/user/email/$defaultUserEmail") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "PUT /admin/user/{userSubjectId}/(re|dis)able" should "disable and then re-enable a user (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
    Post("/register/user/v1/") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Put(s"/api/admin/user/$defaultUserId/disable") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> false, "allUsersGroup" -> true, "google" -> true))
    }

    Put(s"/api/admin/user/$defaultUserId/enable") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
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

  "DELETE /admin/user/{userSubjectId}" should "delete a user (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
    Post("/register/user/v1/") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Delete(s"/api/admin/user/$defaultUserId") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    Get(s"/api/admin/user/$defaultUserId") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "not allow a non-admin to delete a user" in withAdminRoutes { (samRoutes, _) =>
    Delete(s"/api/admin/user/$defaultUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "DELETE /admin/user/{userSubjectId}/petServiceAccount/{project}" should "delete a pet (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
    Post("/register/user/v1/") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Delete(s"/api/admin/user/$defaultUserId/petServiceAccount/myproject") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "not allow a non-admin to delete a pet" in withAdminRoutes { (samRoutes, _) =>
    Delete(s"/api/admin/user/$defaultUserId/petServiceAccount/myproject") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }
}
