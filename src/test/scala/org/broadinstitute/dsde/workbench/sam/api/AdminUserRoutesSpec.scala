package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.model.{AzureB2CId, GoogleSubjectId, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.TestSupport.enabledMapNoTosAccepted
import org.broadinstitute.dsde.workbench.sam.matchers.BeForUserMatcher.beForUser
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api.{AdminUpdateUserRequest, SamUser}
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
      .callAsAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
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
      .callAsNonAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
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
      .callAsAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
      .build
    // Act and Assert
    Get(s"/api/admin/v1/user/$badUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PATCH /admin/v1/user/{userSubjectId}" should "update a user's googleSubjectId" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .callAsAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .build
    val newGoogleSubjectId = Some(GoogleSubjectId("newGoogleSubjectId"))
    val requestBody = AdminUpdateUserRequest(None, newGoogleSubjectId)
    // Act
    Patch(s"/api/admin/v1/user/$defaultUserId", requestBody) ~> samRoutes.route ~> check {
      // Assert
      withClue(s"Response Body: ${responseAs[String]}")(status shouldEqual StatusCodes.OK)
      // Enabled in particular since we cant directly extract the user from the builder
      responseAs[SamUser] shouldEqual defaultUser.copy(googleSubjectId = newGoogleSubjectId)
    }
  }

  "PATCH /admin/v1/user/{userSubjectId}" should "null a user's googleSubjectId if it is set to 'null'" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .callAsAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
      .build
    val requestBody = AdminUpdateUserRequest(None, Some(GoogleSubjectId("null")))
    // Act
    Patch(s"/api/admin/v1/user/$defaultUserId", requestBody) ~> samRoutes.route ~> check {
      // Assert
      withClue(s"Response Body: ${responseAs[String]}")(status shouldEqual StatusCodes.OK)
      // Enabled in particular since we cant directly extract the user from the builder
      responseAs[SamUser] shouldEqual defaultUser.copy(googleSubjectId = None)
    }
  }

  "PATCH /admin/v1/user/{userSubjectId}" should "update a user's azureB2CId" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .callAsAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
      .build
    val newAzureB2CId = Some(AzureB2CId("0000-0000-0000-0000"))
    val requestBody = AdminUpdateUserRequest(newAzureB2CId, None)
    // Act
    Patch(s"/api/admin/v1/user/$defaultUserId", requestBody) ~> samRoutes.route ~> check {
      // Assert
      withClue(s"Response Body: ${responseAs[String]}")(status shouldEqual StatusCodes.OK)
      // Enabled in particular since we cant directly extract the user from the builder
      responseAs[SamUser] shouldEqual defaultUser.copy(azureB2CId = newAzureB2CId)
    }
  }

  "PATCH /admin/v1/user/{userSubjectId}" should "null a user's azureB2CId if it is set to 'null'" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .callAsAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
      .build
    val requestBody = AdminUpdateUserRequest(Some(AzureB2CId("null")), None)
    // Act
    Patch(s"/api/admin/v1/user/$defaultUserId", requestBody) ~> samRoutes.route ~> check {
      // Assert
      withClue(s"Response Body: ${responseAs[String]}")(status shouldEqual StatusCodes.OK)
      // Enabled in particular since we cant directly extract the user from the builder
      responseAs[SamUser] shouldEqual defaultUser.copy(azureB2CId = None)
    }
  }

  "GET /admin/v1/user/email/{email}" should "get the user status of a user by email if the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .callAsAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
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
      .callAsAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
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
      .callAsAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser)
      .withAllowedUser(defaultUser)
      .build
    // Act
    Get(s"/api/admin/v1/user/email/$adminGroupEmail") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "forbid getting a user status if the requesting user is a non admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .callAsNonAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
      .build
    // Act
    Get(s"/api/admin/v1/user/email/$defaultUserEmail") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "PUT /admin/v1/user/{userSubjectId}/disable" should "disable a user if the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .callAsAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
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
      .callAsAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
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
      .callAsNonAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
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
      .callAsAdminUser() // enabled "admin" user who is making the http request
      .withDisabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
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
      .callAsAdminUser()
      .withDisabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
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
      .callAsNonAdminUser()
      .withDisabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
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
      .callAsAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
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
      .callAsNonAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
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
      .callAsAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
      .build
    // Act
    Delete(s"/api/admin/v1/user/$defaultUserId/petServiceAccount/myproject") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "forbid deleting a pet if the requesting user is a non admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .callAsNonAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
      .build
    // Act
    Delete(s"/api/admin/v1/user/$defaultUserId/petServiceAccount/myproject") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "GET /admin/v2/user/{userId}" should "get the corresponding user if the requesting user is an admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .callAsAdminUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
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
      .callAsNonAdminUser()
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser)
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
      .withAllowedUser(defaultUser)
      .callAsNonAdminUser()
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
      .withAllowedUser(defaultUser)
      .callAsAdminUser()
      .build

    // Act and Assert
    Get(s"/api/admin/v2/user/$badUserId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }
}
