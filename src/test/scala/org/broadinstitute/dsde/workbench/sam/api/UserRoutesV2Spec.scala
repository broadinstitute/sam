package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail}
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._
import org.broadinstitute.dsde.workbench.sam.matchers.BeForSamUserResponseMatcher.beForUser
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api.{SamUser, SamUserAllowances, SamUserResponse}
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UserRoutesV2Spec extends AnyFlatSpec with Matchers with ScalatestRouteTest with MockitoSugar with TestSupport {
  val defaultUser: SamUser = Generator.genWorkbenchUserGoogle.sample.get
  val otherUser: SamUser = Generator.genWorkbenchUserGoogle.sample.get
  val adminGroupEmail: WorkbenchEmail = Generator.genFirecloudEmail.sample.get
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

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
      responseAs[SamUserAllowances] should be(SamUserAllowances(allowed = true, enabledInDatabase = true, termsOfService = true))
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
      responseAs[SamUserAllowances] should be(SamUserAllowances(allowed = true, enabledInDatabase = true, termsOfService = true))
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
    Get(s"/api/users/v2/${otherUser.id}/allowed") ~> samRoutes.route ~> check {
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
    Get(s"/api/users/v2/${defaultUser.id}/allowed") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUserAllowances] should be(SamUserAllowances(allowed = true, enabledInDatabase = true, termsOfService = true))
    }

    Get(s"/api/users/v2/${otherUser.id}/allowed") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[SamUserAllowances] should be(SamUserAllowances(allowed = false, enabledInDatabase = false, termsOfService = false))
    }
  }
}
