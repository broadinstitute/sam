package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.TestSupport.{enabledMapNoTosAccepted, genSamDependencies, genSamRoutes}
import org.broadinstitute.dsde.workbench.sam.dataAccess.MockDirectoryDaoBuilder
import org.broadinstitute.dsde.workbench.sam.google.MockHttpGoogleDirectoryDaoBuilder
import org.broadinstitute.dsde.workbench.sam.matchers.BeForUserMatcher.beForUser
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.{Generator, SamDependencies, TestSupport}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future

class AdminUserRoutesSpec extends AdminUserRoutesSpecHelper {
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))
  val cloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
  val tosService = MockTosServiceBuilder().withAllAccepted().build
  val adminUser = Generator.genWorkbenchUserBoth.sample.get
  override val adminGroupEmail = Generator.genFirecloudEmail.sample.get

  "GET /admin/v1/user/{userSubjectId}" should "get the user status of a user (as an admin)" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser(adminUser)     // enabled "admin" user who is making the http request
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build

    // Act and Assert
    Get(s"/api/admin/v1/user/${enabledUser.id}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] should beForUser(enabledUser)
    }
  }

  it should "not allow a non-admin to get the status of another user" in withAdminRoutes { (samRoutes, _) =>
    Get(s"/api/admin/v1/user/$defaultUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "GET /admin/v1/user/email/{email}" should "get the user status of a user by email (as an admin)" in {
    val (user, adminRoutes) = setUpAdminTest()

    Get(s"/api/admin/v1/user/email/${user.email}") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), enabledMapNoTosAccepted)
    }
  }

  it should "return 404 for an unknown user by email (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
    Get(s"/api/admin/v1/user/email/XXX${defaultUserEmail}XXX") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "return 404 for an group's email (as an admin)" in withAdminRoutes { (samRoutes, adminRoutes) =>
    Get(s"/api/admin/v1/user/email/fc-admins@dev.test.firecloud.org") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "not allow a non-admin to get the status of another user" in withAdminRoutes { (samRoutes, _) =>
    Get(s"/api/admin/v1/user/email/$defaultUserEmail") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "PUT /admin/v1/user/{userSubjectId}/(re|dis)able" should "disable and then re-enable a user (as an admin)" in {
    val (user, adminRoutes) = setUpAdminTest()

    Put(s"/api/admin/v1/user/${user.id}/disable") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(
        UserStatusDetails(user.id, user.email),
        enabledMapNoTosAccepted + ("ldap" -> false) + ("adminEnabled" -> false)
      )
    }

    Put(s"/api/admin/v1/user/${user.id}/enable") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(user.id, user.email), enabledMapNoTosAccepted)
    }
  }

  it should "not allow a non-admin to enable or disable a user" in withAdminRoutes { (samRoutes, _) =>
    Put(s"/api/admin/v1/user/$defaultUserId/disable") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }

    Put(s"/api/admin/v1/user/$defaultUserId/enable") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "DELETE /admin/v1/user/{userSubjectId}" should "delete a user (as an admin)" in {
    val (user, adminRoutes) = setUpAdminTest()

    Delete(s"/api/admin/v1/user/${user.id}") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    Get(s"/api/admin/v1/user/${user.id}") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "not allow a non-admin to delete a user" in withAdminRoutes { (samRoutes, _) =>
    Delete(s"/api/admin/v1/user/$defaultUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "DELETE /admin/v1/user/{userSubjectId}/petServiceAccount/{project}" should "delete a pet (as an admin)" in {
    val (user, adminRoutes) = setUpAdminTest()

    Delete(s"/api/admin/v1/user/${user.id}/petServiceAccount/myproject") ~> adminRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "not allow a non-admin to delete a pet" in withAdminRoutes { (samRoutes, _) =>
    Delete(s"/api/admin/v1/user/$defaultUserId/petServiceAccount/myproject") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }
}

trait AdminUserRoutesSpecHelper extends AnyFlatSpec with Matchers with ScalatestRouteTest with MockitoSugar with TestSupport {
  val defaultUser = Generator.genWorkbenchUserGoogle.sample.get
  val defaultUserId = defaultUser.id
  val defaultUserEmail = defaultUser.email
  val adminGroupEmail = Generator.genFirecloudEmail.sample.get

  val adminUserFoo = Generator.genWorkbenchUserGoogle.sample.get.copy(enabled = true)

  val petSAUser = Generator.genWorkbenchUserServiceAccount.sample.get
  val petSAUserId = petSAUser.id
  val petSAEmail = petSAUser.email

  def setUpAdminTest(): (SamUser, SamRoutes) = {
    val googDirectoryDAO = new MockGoogleDirectoryDAO()
    val cloudExtensions = new NoExtensions {
      override def isWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = googDirectoryDAO.isGroupMember(adminGroupEmail, memberEmail)
    }
    val (_, _, routes) =
      createTestUser(testUser = adminUserFoo, cloudExtensions = Option(cloudExtensions), googleDirectoryDAO = Option(googDirectoryDAO), tosAccepted = true)
    val user = Generator.genWorkbenchUserGoogle.sample.get
    runAndWait(routes.userService.createUser(user, samRequestContext))
    (user.copy(enabled = true), routes) // userService.createUser enables the user
  }

  def createTestUser(
      testUser: SamUser = Generator.genWorkbenchUserBoth.sample.get,
      cloudExtensions: Option[CloudExtensions] = None,
      googleDirectoryDAO: Option[GoogleDirectoryDAO] = None,
      tosAccepted: Boolean = false
  ): (SamUser, SamDependencies, SamRoutes) = {
    val samDependencies = genSamDependencies(cloudExtensions = cloudExtensions, googleDirectoryDAO = googleDirectoryDAO)
    val routes = genSamRoutes(samDependencies, testUser)

    Post("/register/user/v1/") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userEmail shouldBe testUser.email
      val enabledBaseArray = Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true, "tosAccepted" -> false, "adminEnabled" -> true)
      res.enabled shouldBe enabledBaseArray
    }

    if (tosAccepted) {
      Post("/register/user/v1/termsofservice", TermsOfServiceAcceptance("app.terra.bio/#terms-of-service")) ~> routes.route ~> check {
        status shouldEqual StatusCodes.OK
        val res = responseAs[UserStatus]
        res.userInfo.userEmail shouldBe testUser.email
        val enabledBaseArray = Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true, "tosAccepted" -> true, "adminEnabled" -> true)
        res.enabled shouldBe enabledBaseArray
      }
    }

    (testUser, samDependencies, routes)
  }

  def withAdminRoutes[T](testCode: (TestSamRoutes, TestSamRoutes) => T): T = {
    val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

    val googleDirectoryDAO = MockHttpGoogleDirectoryDaoBuilder().build
    val directoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
    val tosService = MockTosServiceBuilder().withAllAccepted().build

    val cloudExtensions = new NoExtensions {
      override def isWorkbenchAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = googleDirectoryDAO.isGroupMember(adminGroupEmail, memberEmail)
    }

    directoryDAO.createUser(adminUserFoo, samRequestContext).unsafeRunSync()
    tosService.acceptTosStatus(adminUserFoo.id, samRequestContext).unsafeRunSync()

    val samRoutes = new TestSamRoutes(
      null,
      null,
      new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService),
      new StatusService(directoryDAO, NoExtensions),
      null,
      defaultUser,
      cloudExtensions,
      tosService = tosService
    )
    val adminRoutes = new TestSamRoutes(
      null,
      null,
      new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService),
      new StatusService(directoryDAO, NoExtensions),
      null,
      adminUser,
      cloudExtensions,
      tosService = tosService
    )
    testCode(samRoutes, adminRoutes)
  }
}
