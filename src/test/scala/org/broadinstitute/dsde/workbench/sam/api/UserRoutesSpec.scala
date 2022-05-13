package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport.{genSamDependencies, genSamRoutes, googleServicesConfig}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{MockDirectoryDAO, MockRegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
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
  val defaultUser = Generator.genWorkbenchUserGoogle.sample.get
  val defaultUserId = defaultUser.id
  val defaultUserEmail = defaultUser.email

  val adminUser = Generator.genWorkbenchUserGoogle.sample.get

  val petSAUser = Generator.genWorkbenchUserServiceAccount.sample.get
  val petSAUserId = petSAUser.id
  val petSAEmail = petSAUser.email


  def setupAdminsGroup(googleDirectoryDAO: MockGoogleDirectoryDAO): Future[WorkbenchEmail] = {
    val adminGroupEmail = WorkbenchEmail("fc-admins@dev.test.firecloud.org")
    for {
      _ <- googleDirectoryDAO.createGroup(WorkbenchGroupName("fc-admins"), adminGroupEmail)
      _ <- googleDirectoryDAO.addMemberToGroup(adminGroupEmail, WorkbenchEmail(adminUser.email.value))
    } yield adminGroupEmail
  }

  def setUpAdminTest(): (SamUser, SamRoutes) = {
    val googDirectoryDAO = new MockGoogleDirectoryDAO()
    val adminGroupEmail = runAndWait(setupAdminsGroup(googDirectoryDAO))
    val cloudExtensions = new NoExtensions {
      override def isWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = googDirectoryDAO.isGroupMember(adminGroupEmail, memberEmail)
    }
    val (_, _, routes) = createTestUser(testUser = adminUser, cloudExtensions = Option(cloudExtensions), googleDirectoryDAO = Option(googDirectoryDAO))
    val user = Generator.genWorkbenchUserGoogle.sample.get
    runAndWait(routes.userService.createUser(user, samRequestContext))
    (user.copy(enabled = true), routes) // userService.createUser enables the user
  }

  def createTestUser(testUser: SamUser = Generator.genWorkbenchUserBoth.sample.get, cloudExtensions: Option[CloudExtensions] = None, googleDirectoryDAO: Option[GoogleDirectoryDAO] = None, tosEnabled: Boolean = false, tosAccepted: Boolean = false): (SamUser, SamDependencies, SamRoutes) = {
    val samDependencies = genSamDependencies(cloudExtensions = cloudExtensions, googleDirectoryDAO = googleDirectoryDAO, tosEnabled = tosEnabled)
    val routes = genSamRoutes(samDependencies, testUser)

    Post("/register/user/v1/") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userEmail shouldBe testUser.email
      val enabledBaseArray = Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)

      if (tosEnabled) res.enabled shouldBe  enabledBaseArray + ("tosAccepted" -> false) + ("adminEnabled" -> true)
      else res.enabled shouldBe enabledBaseArray
    }

    if (tosEnabled && tosAccepted) {
      Post("/register/user/v1/termsofservice", TermsOfServiceAcceptance("app.terra.bio/#terms-of-service")) ~> routes.route ~> check {
        status shouldEqual StatusCodes.OK
        val res = responseAs[UserStatus]
        res.userInfo.userEmail shouldBe testUser.email
        val enabledBaseArray = Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)
        res.enabled shouldBe enabledBaseArray + ("tosAccepted" -> true) + ("adminEnabled" -> true)
      }
    }

    (testUser, samDependencies, routes)
  }

  def withAdminRoutes[T](testCode: (TestSamRoutes, TestSamRoutes) => T): T = {
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val registrationDAO = new MockRegistrationDAO()

    val adminGroupEmail = runAndWait(setupAdminsGroup(googleDirectoryDAO))

    val cloudExtensions = new NoExtensions {
      override def isWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = googleDirectoryDAO.isGroupMember(adminGroupEmail, memberEmail)
    }

    directoryDAO.createUser(adminUser, samRequestContext).unsafeRunSync()

    val samRoutes = new TestSamRoutes(null, null, new UserService(directoryDAO, cloudExtensions, registrationDAO, Seq.empty, new TosService(directoryDAO, registrationDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig)), new StatusService(directoryDAO, registrationDAO, NoExtensions, TestSupport.dbRef), null, defaultUser, directoryDAO, registrationDAO, cloudExtensions)
    val adminRoutes = new TestSamRoutes(null, null, new UserService(directoryDAO, cloudExtensions, registrationDAO, Seq.empty, new TosService(directoryDAO, registrationDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig)), new StatusService(directoryDAO, registrationDAO, NoExtensions, TestSupport.dbRef), null, adminUser, directoryDAO, registrationDAO, cloudExtensions)
    testCode(samRoutes, adminRoutes)
  }

  def withDefaultRoutes[T](testCode: TestSamRoutes => T): T = {
    val directoryDAO = new MockDirectoryDAO()
    val registrationDAO = new MockRegistrationDAO()

    val samRoutes = new TestSamRoutes(null, null, new UserService(directoryDAO, NoExtensions, registrationDAO, Seq.empty, new TosService(directoryDAO, registrationDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig)), new StatusService(directoryDAO, registrationDAO, NoExtensions, TestSupport.dbRef), null, defaultUser, directoryDAO, registrationDAO,
      newSamUser = Option(defaultUser))

    testCode(samRoutes)
  }

  def withTosEnabledRoutes[T](testCode: TestSamTosEnabledRoutes => T): T = {
    val directoryDAO = new MockDirectoryDAO()
    val registrationDAO = new MockRegistrationDAO()

    val tosService = new TosService(directoryDAO, registrationDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig.copy(enabled = true))

    val samRoutes = new TestSamTosEnabledRoutes(null, null, new UserService(directoryDAO, NoExtensions, registrationDAO, Seq.empty, tosService), new StatusService(directoryDAO, registrationDAO, NoExtensions, TestSupport.dbRef), null, defaultUser, directoryDAO, registrationDAO,
      newSamUser = Option(defaultUser), tosService = tosService)

    testCode(samRoutes)
  }
}
