package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.TestSupport.enabledMapNoTosAccepted
import org.broadinstitute.dsde.workbench.sam.matchers.BeForUserMatcher.beForUser
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AdminUserRoutesSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with MockitoSugar with TestSupport {
  val defaultUser: SamUser = Generator.genWorkbenchUserGoogle.sample.get
  val defaultUserId: WorkbenchUserId = defaultUser.id
  val defaultUserEmail: WorkbenchEmail = defaultUser.email
  val adminGroupEmail: WorkbenchEmail = Generator.genFirecloudEmail.sample.get
  val adminUser: SamUser = Generator.genWorkbenchUserBoth.sample.get
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))
  private val badUserId = WorkbenchUserId(s"-$defaultUserId")
  private val newUserEmail = WorkbenchEmail(s"XXX${defaultUserEmail}XXX")

  "GET /admin/v1/user/{userSubjectId}" should "get the user status of a user (as an admin)" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser(adminUser) // enabled "admin" user who is making the http request
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build

    // Act and Assert
    Get(s"/api/admin/v1/user/${enabledUser.id}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] should beForUser(enabledUser)
    }
  }

  it should "not allow a non-admin to get the status of another user" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withNonAdminUser(enabledUser)
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build

    // Act
    Get(s"/api/admin/v1/user/$defaultUserId") ~> samRoutes.route ~> check {
      // Assert
      withClue(s"Response Body: ${responseAs[String]}")(status shouldEqual StatusCodes.Forbidden)
    }
  }

  it should "not find a user that does not exist" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser(adminUser) // enabled "admin" user who is making the http request
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build
    // Act and Assert
    Get(s"/api/admin/v1/user/$badUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PATCH /admin/v1/user/{userSubjectId}" should "update a user" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser(adminUser) // enabled "admin" user who is making the http request
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build
    val requestBody = AdminUpdateUserRequest(None, Some(newUserEmail), None, None, None)
    // Act
    Patch(s"/api/admin/v1/user/${enabledUser.id}", requestBody) ~> samRoutes.route ~> check {
      // Assert
      withClue(s"Response Body: ${responseAs[String]}")(status shouldEqual StatusCodes.OK)
      // Enabled in particular since we cant directly extract the user from the builder
      responseAs[SamUser] shouldEqual enabledUser.copy(email = newUserEmail)
    }
  }

  it should "not update a user with an invalid email in the request" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser(adminUser)
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .withBadEmail()
      .build
    val requestBody = AdminUpdateUserRequest(None, Some(newUserEmail), None, None, None)

    // Act
    Patch(s"/api/admin/v1/user/$enabledUser.id", requestBody) ~> samRoutes.route ~> check {
      // Assert
      withClue(s"Response Body: ${responseAs[String]}")(status shouldEqual StatusCodes.BadRequest)
    }
  }

  it should "not update a user for a user that does not exist" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser(adminUser)
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build
    val requestBody = AdminUpdateUserRequest(None, Some(newUserEmail), None, None, None)

    // Act
    Patch(s"/api/admin/v1/user/$badUserId", requestBody) ~> samRoutes.route ~> check {
      // Assert
      withClue(s"Response Body: ${responseAs[String]}")(status shouldEqual StatusCodes.NotFound)
    }
  }

  it should "not allow a non-admin to update a user" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withNonAdminUser(enabledUser)
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build
    val requestBody = AdminUpdateUserRequest(None, Some(newUserEmail), None, None, None)

    // Act
    Patch(s"/api/admin/v1/user/${enabledUser.id}", requestBody) ~> samRoutes.route ~> check {
      // Assert
      withClue(s"Response Body: ${responseAs[String]}")(status shouldEqual StatusCodes.Forbidden)
    }
  }

  it should "not allow a non-admin to update a user for a user that does not exist" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withNonAdminUser(enabledUser)
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build
    val requestBody = AdminUpdateUserRequest(None, Some(newUserEmail), None, None, None)

    // Act
    Patch(s"/api/admin/v1/user/$badUserId", requestBody) ~> samRoutes.route ~> check {
      // Assert
      withClue(s"Response Body: ${responseAs[String]}")(status shouldEqual StatusCodes.Forbidden)
    }
  }

  "GET /admin/v1/user/email/{email}" should "get the user status of a user by email (as an admin)" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser(adminUser) // enabled "admin" user who is making the http request
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build

    // Act
    Get(s"/api/admin/v1/user/email/${enabledUser.email}") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(enabledUser.id, enabledUser.email), enabledMapNoTosAccepted)
    }
  }

  it should "return 404 for an unknown user by email (as an admin)" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser(adminUser) // enabled "admin" user who is making the http request
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Get(s"/api/admin/v1/user/email/$newUserEmail") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "return 404 for an group's email (as an admin)" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser(adminUser) // enabled "admin" user who is making the http request
      .build
    // Act
    Get(s"/api/admin/v1/user/email/fc-admins@dev.test.firecloud.org") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "not allow a non-admin to get the status of another user" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withNonAdminUser(enabledUser)
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Get(s"/api/admin/v1/user/email/$defaultUserEmail") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "PUT /admin/v1/user/{userSubjectId}/disable" should "disable a user (as an admin)" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser(adminUser) // enabled "admin" user who is making the http request
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Put(s"/api/admin/v1/user/${enabledUser.id}/disable") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(
        UserStatusDetails(enabledUser.id, enabledUser.email),
        enabledMapNoTosAccepted + ("ldap" -> false) + ("adminEnabled" -> false)
      )
    }
  }

  it should "not disable a user that does not exist" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser(enabledUser)
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Put(s"/api/admin/v1/user/$badUserId/disable") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "not allow a non-admin to disable a user" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withNonAdminUser(enabledUser)
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Put(s"/api/admin/v1/user/$defaultUserId/disable") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "PUT /admin/v1/user/{userSubjectId}/enable" should "enable a user (as an admin)" in {
    // Arrange
    val user = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser(adminUser) // enabled "admin" user who is making the http request
      .withDisabledUser(user) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Put(s"/api/admin/v1/user/${user.id}/enable") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(
        UserStatusDetails(user.id, user.email),
        enabledMapNoTosAccepted
      )
    }
  }

  it should "not enable a user that does not exist" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser(enabledUser)
      .withDisabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Put(s"/api/admin/v1/user/$badUserId/enable") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "not allow a non-admin to enable a user" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withNonAdminUser(enabledUser)
      .withDisabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Put(s"/api/admin/v1/user/$defaultUserId/enable") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "DELETE /admin/v1/user/{userSubjectId}" should "delete a user (as an admin)" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser(adminUser) // enabled "admin" user who is making the http request
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Delete(s"/api/admin/v1/user/${enabledUser.id}") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.OK
    }
  }

  it should "not allow a non-admin to delete a user" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withNonAdminUser(enabledUser)
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Delete(s"/api/admin/v1/user/$defaultUserId") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "DELETE /admin/v1/user/{userSubjectId}/petServiceAccount/{project}" should "delete a pet (as an admin)" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser(adminUser) // enabled "admin" user who is making the http request
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Delete(s"/api/admin/v1/user/${enabledUser.id}/petServiceAccount/myproject") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "not allow a non-admin to delete a pet" in {
    // Arrange
    val enabledUser = Generator.genWorkbenchUserBoth.sample.get
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withNonAdminUser(enabledUser)
      .withEnabledUser(enabledUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Delete(s"/api/admin/v1/user/$defaultUserId/petServiceAccount/myproject") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }
}
