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
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))
  private val badUserId = WorkbenchUserId(s"-$defaultUserId")
  private val newUserEmail = WorkbenchEmail(s"XXX${defaultUserEmail}XXX")

  "GET /admin/v1/user/{userSubjectId}" should "get the user status of a user if the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build

    // Act and Assert
    Get(s"/api/admin/v1/user/${defaultUserId}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] should beForUser(defaultUser)
    }
  }

  it should "forbid getting a user status if the requesting user is a non admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withNonAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build

    // Act
    Get(s"/api/admin/v1/user/$defaultUserId") ~> samRoutes.route ~> check {
      // Assert
      withClue(s"Response Body: ${responseAs[String]}")(status shouldEqual StatusCodes.Forbidden)
    }
  }

  it should "not find a user status if that user does not exist and the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    // Act and Assert
    Get(s"/api/admin/v1/user/$badUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PATCH /admin/v1/user/{userSubjectId}" should "update a user if the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    val requestBody = AdminUpdateUserRequest(None, Some(newUserEmail), None, None, None)
    // Act
    Patch(s"/api/admin/v1/user/$defaultUserId", requestBody) ~> samRoutes.route ~> check {
      // Assert
      withClue(s"Response Body: ${responseAs[String]}")(status shouldEqual StatusCodes.OK)
      // Enabled in particular since we cant directly extract the user from the builder
      responseAs[SamUser] shouldEqual defaultUser.copy(email = newUserEmail)
    }
  }

  it should "report an error when updating a user if the request email is invalid and the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withBadEmail()
      .build
    val requestBody = AdminUpdateUserRequest(None, Some(newUserEmail), None, None, None)

    // Act
    Patch(s"/api/admin/v1/user/$defaultUserId", requestBody) ~> samRoutes.route ~> check {
      // Assert
      withClue(s"Response Body: ${responseAs[String]}")(status shouldEqual StatusCodes.BadRequest)
    }
  }

  it should "not find a user when trying to update a user if the user does not exist and the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    val requestBody = AdminUpdateUserRequest(None, Some(newUserEmail), None, None, None)

    // Act
    Patch(s"/api/admin/v1/user/$badUserId", requestBody) ~> samRoutes.route ~> check {
      // Assert
      withClue(s"Response Body: ${responseAs[String]}")(status shouldEqual StatusCodes.NotFound)
    }
  }

  it should "forbid updating a user if the requesting user is a non admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withNonAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    val requestBody = AdminUpdateUserRequest(None, Some(newUserEmail), None, None, None)

    // Act
    Patch(s"/api/admin/v1/user/$defaultUserId", requestBody) ~> samRoutes.route ~> check {
      // Assert
      withClue(s"Response Body: ${responseAs[String]}")(status shouldEqual StatusCodes.Forbidden)
    }
  }

  it should "forbid updating a user for a user if the user does not exist and the requesting user is a non admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withNonAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    val requestBody = AdminUpdateUserRequest(None, Some(newUserEmail), None, None, None)

    // Act
    Patch(s"/api/admin/v1/user/$badUserId", requestBody) ~> samRoutes.route ~> check {
      // Assert
      withClue(s"Response Body: ${responseAs[String]}")(status shouldEqual StatusCodes.Forbidden)
    }
  }

  "GET /admin/v1/user/email/{email}" should "get the user status of a user by email if the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build

    // Act
    Get(s"/api/admin/v1/user/email/$defaultUserEmail") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), enabledMapNoTosAccepted)
    }
  }

  it should "not find a user status by email if the user does not exist and the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Get(s"/api/admin/v1/user/email/$newUserEmail") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "not find a group by email if the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser)
      .build
    // Act
    Get(s"/api/admin/v1/user/email/$adminGroupEmail") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "forbid getting a user status if the requesting user is a non admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withNonAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Get(s"/api/admin/v1/user/email/$defaultUserEmail") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "PUT /admin/v1/user/{userSubjectId}/disable" should "disable a user if the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Put(s"/api/admin/v1/user/$defaultUserId/disable") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(
        UserStatusDetails(defaultUserId, defaultUserEmail),
        enabledMapNoTosAccepted + ("ldap" -> false) + ("adminEnabled" -> false)
      )
    }
  }

  it should "not find a user when trying to disable a user if the user does not exist and the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Put(s"/api/admin/v1/user/$badUserId/disable") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "forbid the disabling of a user if the requesting user is a non admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withNonAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Put(s"/api/admin/v1/user/$defaultUserId/disable") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "PUT /admin/v1/user/{userSubjectId}/enable" should "enable a user if the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser() // enabled "admin" user who is making the http request
      .withDisabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Put(s"/api/admin/v1/user/$defaultUserId/enable") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.OK
      responseAs[UserStatus] shouldEqual UserStatus(
        UserStatusDetails(defaultUserId, defaultUserEmail),
        enabledMapNoTosAccepted
      )
    }
  }

  it should "not find a user when trying to enable a user if the user does not exist and the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser()
      .withDisabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Put(s"/api/admin/v1/user/$badUserId/enable") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "forbid enabling a user if the requesting user is a non admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withNonAdminUser()
      .withDisabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Put(s"/api/admin/v1/user/$defaultUserId/enable") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "DELETE /admin/v1/user/{userSubjectId}" should "delete a user if the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Delete(s"/api/admin/v1/user/$defaultUserId") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.OK
    }
  }

  it should "forbid deleting a user if the requesting user is a non admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withNonAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Delete(s"/api/admin/v1/user/$defaultUserId") ~> samRoutes.route ~> check {
      // Assert
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "DELETE /admin/v1/user/{userSubjectId}/petServiceAccount/{project}" should "delete a pet if the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Delete(s"/api/admin/v1/user/$defaultUserId/petServiceAccount/myproject") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "forbid deleting a pet if the requesting user is a non admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withNonAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    // Act
    Delete(s"/api/admin/v1/user/$defaultUserId/petServiceAccount/myproject") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "GET /admin/v2/user/{userId}" should "get the corresponding user if the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build

    // Act and Assert
    Get(s"/api/admin/v2/user/$defaultUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUser] shouldEqual defaultUser
    }
  }

  it should "forbid getting a user if the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withNonAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build

    // Act and Assert
    Get(s"/api/admin/v2/user/$defaultUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "forbid getting a user even if a user does not exist and the requesting user is a non admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withNonAdminUser()
      .build

    // Act and Assert
    Get(s"/api/admin/v2/user/$badUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "not find a user when trying to get a user if it does not exist and the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAdminUser()
      .build

    // Act and Assert
    Get(s"/api/admin/v2/user/$badUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }
}
