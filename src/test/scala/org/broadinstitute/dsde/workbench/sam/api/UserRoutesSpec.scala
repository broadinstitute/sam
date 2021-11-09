package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport.{genSamDependencies, genSamRoutes, googleServicesConfig}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{MockDirectoryDAO, MockRegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.UserService.genWorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.service._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future

/**
  * Created by dvoet on 6/7/17.
  */
class UserRoutesSpec extends UserRoutesSpecHelper {
  "POST /register/user" should "create user" in withDefaultRoutes { samRoutes =>
    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userSubjectId.value.length shouldBe 21
      res.userInfo.userEmail shouldBe defaultUserEmail
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)
    }

    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }

  "GET /register/user" should "get the status of an enabled user" in {
    val (user, _, routes) = createTestUser()

    Get("/register/user") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  "GET /admin/user/{userSubjectId}" should "get the user status of a user (as an admin)" in {
    val (user, adminRoutes) = setUpAdminTest()

    Get(s"/api/admin/user/${user.id}") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }
  }

  it should "not allow a non-admin to get the status of another user" in withAdminRoutes { (samRoutes, _) =>
    Get(s"/api/admin/user/$defaultUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "GET /admin/user/email/{email}" should "get the user status of a user by email (as an admin)" in {
    val (user, adminRoutes) = setUpAdminTest()

    Get(s"/api/admin/user/email/${user.email}") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
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

  "PUT /admin/user/{userSubjectId}/(re|dis)able" should "disable and then re-enable a user (as an admin)" in {
    val (user, adminRoutes) = setUpAdminTest()

    Put(s"/api/admin/user/${user.id}/disable") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails({user.id}, user.email), Map("ldap" -> false, "allUsersGroup" -> true, "google" -> true))
    }

    Put(s"/api/admin/user/${user.id}/enable") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails({user.id}, user.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
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

  "DELETE /admin/user/{userSubjectId}" should "delete a user (as an admin)" in {
    val (user, adminRoutes) = setUpAdminTest()

    Delete(s"/api/admin/user/${user.id}") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    Get(s"/api/admin/user/${user.id}") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "not allow a non-admin to delete a user" in withAdminRoutes { (samRoutes, _) =>
    Delete(s"/api/admin/user/$defaultUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "DELETE /admin/user/{userSubjectId}/petServiceAccount/{project}" should "delete a pet (as an admin)" in {
    val (user, adminRoutes) = setUpAdminTest()

    Delete(s"/api/admin/user/${user.id}/petServiceAccount/myproject") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "not allow a non-admin to delete a pet" in withAdminRoutes { (samRoutes, _) =>
    Delete(s"/api/admin/user/$defaultUserId/petServiceAccount/myproject") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }
}

trait UserRoutesSpecHelper extends AnyFlatSpec with Matchers with ScalatestRouteTest with MockitoSugar with TestSupport{
  val defaultUserId = WorkbenchUserId("newuser")
  val defaultUserEmail = WorkbenchEmail("newuser@new.com")

  val adminUserId = WorkbenchUserId("adminuser")
  val adminGoogleSubjectId = GoogleSubjectId("adminuserGoog")
  val adminUserEmail = WorkbenchEmail("adminuser@new.com")

  val petSAUserId = WorkbenchUserId("123")
  val petSAEmail = WorkbenchEmail("pet-newuser@test.iam.gserviceaccount.com")


  def setupAdminsGroup(googleDirectoryDAO: MockGoogleDirectoryDAO): Future[WorkbenchEmail] = {
    val adminGroupEmail = WorkbenchEmail("fc-admins@dev.test.firecloud.org")
    for {
      _ <- googleDirectoryDAO.createGroup(WorkbenchGroupName("fc-admins"), adminGroupEmail)
      _ <- googleDirectoryDAO.addMemberToGroup(adminGroupEmail, WorkbenchEmail(adminUserEmail.value))
    } yield adminGroupEmail
  }

  def setUpAdminTest(): (WorkbenchUser, SamRoutes) = {
    val googDirectoryDAO = new MockGoogleDirectoryDAO()
    val adminGroupEmail = runAndWait(setupAdminsGroup(googDirectoryDAO))
    val cloudExtensions = new NoExtensions {
      override def isWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = googDirectoryDAO.isGroupMember(adminGroupEmail, memberEmail)
    }
    val (_, _, routes) = createTestUser(cloudExtensions = Some(cloudExtensions), googleDirectoryDAO = Some(googDirectoryDAO), userEmail = adminUserEmail)
    val userId = genWorkbenchUserId(System.currentTimeMillis())
    val userStatus = runAndWait(routes.userService.createUser(WorkbenchUser(userId, Option(GoogleSubjectId(userId.value)), defaultUserEmail, None), samRequestContext))
    (WorkbenchUser(userStatus.userInfo.userSubjectId, Some(GoogleSubjectId(userStatus.userInfo.userSubjectId.value)), userStatus.userInfo.userEmail, None), routes)
  }

  def createTestUser(googSubjectId: Option[GoogleSubjectId] = None, cloudExtensions: Option[CloudExtensions] = None, googleDirectoryDAO: Option[GoogleDirectoryDAO] = None, azureB2CId: Option[AzureB2CId] = None, userEmail: WorkbenchEmail = defaultUserEmail): (WorkbenchUser, SamDependencies, SamRoutes) = {
    val userInfo = UserInfo(OAuth2BearerToken(""), genWorkbenchUserId(System.currentTimeMillis()), userEmail, 3600)

    val samDependencies = genSamDependencies(cloudExtensions = cloudExtensions, googleDirectoryDAO = googleDirectoryDAO)
    val routes = genSamRoutes(samDependencies, userInfo)

    // create a user
    val user = Post("/register/user/v1/") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userEmail shouldBe userEmail
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)

      WorkbenchUser(res.userInfo.userSubjectId, Some(GoogleSubjectId(res.userInfo.userSubjectId.value)), res.userInfo.userEmail, azureB2CId)
    }
    (user, samDependencies, routes)
  }

  def withAdminRoutes[T](testCode: (TestSamRoutes, TestSamRoutes) => T): T = {
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val registrationDAO = new MockRegistrationDAO()

    val adminGroupEmail = runAndWait(setupAdminsGroup(googleDirectoryDAO))

    val cloudExtensions = new NoExtensions {
      override def isWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = googleDirectoryDAO.isGroupMember(adminGroupEmail, memberEmail)
    }

    directoryDAO.createUser(WorkbenchUser(adminUserId, None, adminUserEmail, None), samRequestContext).unsafeRunSync()

    val samRoutes = new TestSamRoutes(null, null, new UserService(directoryDAO, cloudExtensions, registrationDAO, Seq.empty, new TosService(directoryDAO, googleServicesConfig.appsDomain)), new StatusService(directoryDAO, registrationDAO, NoExtensions, TestSupport.dbRef), null, UserInfo(OAuth2BearerToken(""), defaultUserId, defaultUserEmail, 0), directoryDAO, cloudExtensions)
    val adminRoutes = new TestSamRoutes(null, null, new UserService(directoryDAO, cloudExtensions, registrationDAO, Seq.empty, new TosService(directoryDAO, googleServicesConfig.appsDomain)), new StatusService(directoryDAO, registrationDAO, NoExtensions, TestSupport.dbRef), null, UserInfo(OAuth2BearerToken(""), adminUserId, adminUserEmail, 0), directoryDAO, cloudExtensions)
    testCode(samRoutes, adminRoutes)
  }

  def withDefaultRoutes[T](testCode: TestSamRoutes => T): T = {
    val directoryDAO = new MockDirectoryDAO()
    val registrationDAO = new MockRegistrationDAO()

    val samRoutes = new TestSamRoutes(null, null, new UserService(directoryDAO, NoExtensions, registrationDAO, Seq.empty, new TosService(directoryDAO, googleServicesConfig.appsDomain)), new StatusService(directoryDAO, registrationDAO, NoExtensions, TestSupport.dbRef), null, UserInfo(OAuth2BearerToken(""), defaultUserId, defaultUserEmail, 0), directoryDAO,
      workbenchUser = Option(WorkbenchUser(UserService.genWorkbenchUserId(System.currentTimeMillis()), TestSupport.genGoogleSubjectId(), defaultUserEmail, None)))

    testCode(samRoutes)
  }

  def withTosEnabledRoutes[T](testCode: TestSamTosEnabledRoutes => T): T = {
    val directoryDAO = new MockDirectoryDAO()
    val registrationDAO = new MockRegistrationDAO()

    val samRoutes = new TestSamTosEnabledRoutes(null, null, new UserService(directoryDAO, NoExtensions, registrationDAO, Seq.empty, new TosService(directoryDAO, googleServicesConfig.appsDomain)), new StatusService(directoryDAO, registrationDAO, NoExtensions, TestSupport.dbRef), null, UserInfo(OAuth2BearerToken(""), defaultUserId, defaultUserEmail, 0), directoryDAO,
      workbenchUser = Option(WorkbenchUser(UserService.genWorkbenchUserId(System.currentTimeMillis()), TestSupport.genGoogleSubjectId(), defaultUserEmail, None)))

    testCode(samRoutes)
  }
}
