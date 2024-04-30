package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._
import org.broadinstitute.dsde.workbench.sam.matchers.BeForSamUserResponseMatcher.beForUser
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api.{
  FilteredResourceFlat,
  FilteredResourcesFlat,
  SamUser,
  SamUserAllowances,
  SamUserAttributes,
  SamUserAttributesRequest,
  SamUserCombinedStateResponse,
  SamUserRegistrationRequest,
  SamUserResponse
}
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import org.broadinstitute.dsde.workbench.sam.matchers.TimeMatchers
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.Mockito.lenient

class UserRoutesV2Spec extends AnyFlatSpec with Matchers with TimeMatchers with ScalatestRouteTest with MockitoSugar with TestSupport {
  val defaultUser: SamUser = Generator.genWorkbenchUserGoogle.sample.get
  val otherUser: SamUser = Generator.genWorkbenchUserGoogle.sample.get
  val thirdUser: SamUser = Generator.genWorkbenchUserGoogle.sample.get
  val adminGroupEmail: WorkbenchEmail = Generator.genFirecloudEmail.sample.get
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  "POST /api/users/v2/self/register" should "register a user when the required attributes are provided" in {
    // Arrange
    val userAttributesRequest = SamUserAttributesRequest(marketingConsent = Some(false))
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUser(defaultUser)
      .withAllowedUser(defaultUser)
      .callAsNonAdminUser(Some(defaultUser))
      .build

    // Act and Assert
    Post(s"/api/users/v2/self/register", SamUserRegistrationRequest(true, userAttributesRequest)) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[SamUserResponse] should beForUser(defaultUser)
    }
  }

  "GET /api/users/v2/self" should "get the user object of the requesting user" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser) // "allowed" user we will check the status of
      .callAsNonAdminUser()
      .build

    // Act and Assert
    Get(s"/api/users/v2/self") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUserResponse] should beForUser(defaultUser)
    }
  }

  "GET /api/users/v2/{sam_user_id}" should "return the regular user if they're getting themselves" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUsers(Seq(defaultUser, otherUser))
      .withAllowedUsers(Seq(defaultUser, otherUser))
      .callAsNonAdminUser()
      .build

    // Act and Assert
    Get(s"/api/users/v2/${defaultUser.id}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUserResponse] should beForUser(defaultUser)
    }
  }

  it should "fail with Not Found if a regular user is getting another user" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUsers(Seq(defaultUser, otherUser))
      .withAllowedUsers(Seq(defaultUser, otherUser))
      .callAsNonAdminUser()
      .build

    // Act and Assert
    Get(s"/api/users/v2/${otherUser.id}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
      val foo = responseAs[ErrorReport]
      foo.message contains "You must be an admin"
    }
  }

  it should "succeed if an admin user is getting another user" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUsers(Seq(defaultUser, otherUser))
      .withAllowedUser(defaultUser)
      .callAsAdminUser()
      .build

    // Act and Assert
    Get(s"/api/users/v2/${defaultUser.id}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUserResponse] should beForUser(defaultUser)
      responseAs[SamUserResponse].allowed should be(true)
    }

    Get(s"/api/users/v2/${otherUser.id}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUserResponse] should beForUser(otherUser)
      responseAs[SamUserResponse].allowed should be(false)
    }
  }

  "GET /api/users/v2/self/allowed" should "get the user allowances of the calling user" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser) // "allowed" user we will check the status of
      .callAsNonAdminUser()
      .build

    // Act and Assert
    Get(s"/api/users/v2/self/allowed") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUserAllowances] should be(SamUserAllowances(enabled = true, termsOfService = true))
    }
  }

  "GET /api/users/v2/{sam_user_id}/allowed" should "return the allowances of a regular user if they're getting themselves" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUsers(Seq(defaultUser, otherUser))
      .withAllowedUsers(Seq(defaultUser, otherUser))
      .callAsNonAdminUser()
      .build

    // Act and Assert
    Get(s"/api/users/v2/${defaultUser.id}/allowed") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUserAllowances] should be(SamUserAllowances(enabled = true, termsOfService = true))
    }
  }

  it should "fail with Not Found if a regular user is getting another user" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUsers(Seq(defaultUser, otherUser))
      .withAllowedUsers(Seq(defaultUser, otherUser))
      .callAsNonAdminUser(Some(defaultUser))
      .build

    // Act and Assert
    Get(s"/api/users/v2/${otherUser.id}/allowed") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
      val foo = responseAs[ErrorReport]
      foo.message contains "You must be an admin"
    }
  }

  it should "succeed if an admin user is getting another user" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUsers(Seq(defaultUser, otherUser, thirdUser))
      .withAllowedUsers(Seq(defaultUser, otherUser))
      .callAsAdminUser(Some(defaultUser))
      .build

    // Act and Assert
    Get(s"/api/users/v2/${otherUser.id}/allowed") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUserAllowances] should be(SamUserAllowances(enabled = true, termsOfService = true))
    }

    Get(s"/api/users/v2/${thirdUser.id}/allowed") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUserAllowances] should be(SamUserAllowances(enabled = false, termsOfService = false))
    }
  }

  "GET /api/users/v2/self/attributes" should "get the user attributes of the calling user" in {
    // Arrange
    val userAttributes = SamUserAttributes(defaultUser.id, marketingConsent = true)

    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser) // "allowed" user we will check the status of
      .withUserAttributes(defaultUser, userAttributes)
      .callAsNonAdminUser()
      .build

    // Act and Assert
    Get(s"/api/users/v2/self/attributes") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUserAttributes] should be(userAttributes)
    }
  }

  "GET /api/users/v2/self/attributes" should "get the user attributes of the calling disallowed user" in {
    // Arrange
    val userAttributes = SamUserAttributes(defaultUser.id, marketingConsent = true)

    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withDisabledUser(defaultUser)
      .withDisallowedUser(defaultUser)
      .withUserAttributes(defaultUser, userAttributes)
      .callAsNonAdminUser()
      .build

    // Act and Assert
    Get(s"/api/users/v2/self/attributes") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUserAttributes] should be(userAttributes)
    }
  }

  it should "return Not Found if the user has no attributes" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser) // "allowed" user we will check the status of
      .callAsNonAdminUser()
      .build

    // Act and Assert
    Get(s"/api/users/v2/self/attributes") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PATCH /api/users/v2/self/attributes" should "update the user attributes of the calling user" in {
    // Arrange
    val userAttributesRequest = SamUserAttributesRequest(marketingConsent = Some(false))
    val userAttributes = SamUserAttributes(defaultUser.id, marketingConsent = false)

    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser) // "allowed" user we will check the status of
      .withUserAttributes(defaultUser, userAttributes)
      .callAsNonAdminUser()
      .build

    // Act and Assert
    Patch(s"/api/users/v2/self/attributes", userAttributesRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUserAttributes] should be(userAttributes)
    }
  }

  "GET /api/users/v2/self/combinedState" should "get the user combined state of the calling user" in {
    // Arrange
    val userAttributes = SamUserAttributes(defaultUser.id, marketingConsent = true)
    val enterpriseFeature = FilteredResourceFlat(
      resourceType = ResourceTypeName("enterprise-feature"),
      resourceId = ResourceId("enterprise-feature"),
      policies = Set.empty,
      roles = Set(ResourceRoleName("user")),
      actions = Set.empty,
      authDomainGroups = Set.empty,
      missingAuthDomainGroups = Set.empty
    )
    val filteresResourcesFlat = FilteredResourcesFlat(Set(enterpriseFeature))
    val userCombinedStateResponse = SamUserCombinedStateResponse(
      defaultUser,
      SamUserAllowances(enabled = true, termsOfService = true),
      SamUserAttributes(defaultUser.id, marketingConsent = true),
      TermsOfServiceDetails("v1", Instant.now(), permitsSystemUsage = true, isCurrentVersion = true),
      FilteredResourcesFlat(Set(enterpriseFeature))
    )

    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser) // "allowed" user we will check the status of
      .withUserAttributes(defaultUser, userAttributes)
      .callAsNonAdminUser()
      .build

    lenient()
      .doReturn(IO.pure(FilteredResourcesFlat(Set(enterpriseFeature))))
      .when(samRoutes.resourceService)
      .listResourcesFlat(
        any[WorkbenchUserId],
        any[Set[ResourceTypeName]],
        any[Set[AccessPolicyName]],
        any[Set[ResourceRoleName]],
        any[Set[ResourceAction]],
        any[Boolean],
        any[SamRequestContext]
      )

    // Act and Assert
    Get(s"/api/users/v2/self/combinedState") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[SamUserCombinedStateResponse]
      response.samUser should be(defaultUser)
      response.allowances should be(userCombinedStateResponse.allowances)
      response.attributes should be(userCombinedStateResponse.attributes)
      response.termsOfServiceDetails.acceptedOn should beAround(userCombinedStateResponse.termsOfServiceDetails.acceptedOn)
      response.termsOfServiceDetails.isCurrentVersion should be(userCombinedStateResponse.termsOfServiceDetails.isCurrentVersion)
      response.termsOfServiceDetails.permitsSystemUsage should be(userCombinedStateResponse.termsOfServiceDetails.permitsSystemUsage)
      response.termsOfServiceDetails.latestAcceptedVersion should be(userCombinedStateResponse.termsOfServiceDetails.latestAcceptedVersion)
      response.enterpriseFeatures should be(filteresResourcesFlat)
    }
  }

  it should "return Not Found if the user has no attributes" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUser(defaultUser) // "persisted/enabled" user we will check the status of
      .withAllowedUser(defaultUser) // "allowed" user we will check the status of
      .callAsNonAdminUser()
      .build

    // Act and Assert
    Get(s"/api/users/v2/self/combinedState") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }
}
