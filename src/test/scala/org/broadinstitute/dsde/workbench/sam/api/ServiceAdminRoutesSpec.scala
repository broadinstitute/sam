package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json.DefaultJsonProtocol.immSetFormat

class ServiceAdminRoutesSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with MockitoSugar with TestSupport {
  val defaultUser: SamUser = Generator.genWorkbenchUserBoth.sample.get
  val defaultUserId: WorkbenchUserId = defaultUser.id
  val defaultUserEmail: WorkbenchEmail = defaultUser.email
  val adminGroupEmail: WorkbenchEmail = Generator.genFirecloudEmail.sample.get
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  "GET /admin/v2/users" should "get the matching user records when provided with matching userId when called as a service admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .callAsAdminServiceUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser)
      .build

    // Act and Assert
    Get(s"/api/admin/v2/users?id=${defaultUserId}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[SamUser]] shouldEqual Set(defaultUser)
    }
  }

  it should "get the matching user records when provided with matching googleSubjectId when called as a service admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .callAsAdminServiceUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser)
      .build

    // Act and Assert
    Get(s"/api/admin/v2/users?googleSubjectId=${defaultUser.googleSubjectId.get.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[SamUser]] shouldEqual Set(defaultUser)
    }
  }

  it should "get the matching user records when provided with matching azureB2CId when called as a service admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .callAsAdminServiceUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser)
      .build

    // Act and Assert
    Get(s"/api/admin/v2/users?azureB2CId=${defaultUser.azureB2CId.get.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[SamUser]] shouldEqual Set(defaultUser)
    }
  }

  it should "get the matching user records when provided with matching unique userId, googleSubjectId, and azureB2CId when called as a service admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .callAsAdminServiceUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser)
      .build

    // Act and Assert
    Get(
      s"/api/admin/v2/users?id=${defaultUser.id}&googleSubjectId=${defaultUser.googleSubjectId.get.value}&azureB2CId=${defaultUser.azureB2CId.get.value}"
    ) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[SamUser]] shouldEqual Set(defaultUser)
    }
  }

  it should "get the matching user records when provided with matching the same userId, googleSubjectId, and azureB2CId when called as a service admin" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .callAsAdminServiceUser() // enabled "admin" user who is making the http request
      .withEnabledUser(defaultUser)
      .build

    // Act and Assert
    Get(s"/api/admin/v2/users?id=${defaultUser.id}&googleSubjectId=${defaultUser.id}&azureB2CId=${defaultUser.id}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[SamUser]] shouldEqual Set(defaultUser)
    }
  }

  it should "get the matching user records when provided with matching a different userId, googleSubjectId, and azureB2CId corresponding to different users when called as a service admin" in {
    // Arrange
    val samUser = Generator.genBroadInstituteUser.sample.get
    val googleUser = Generator.genWorkbenchUserGoogle.sample.get
    val azureUser = Generator.genWorkbenchUserAzure.sample.get
    val users = List(samUser, googleUser, azureUser)
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .callAsAdminServiceUser() // enabled "admin" user who is making the http request
      .withEnabledUsers(users)
      .build

    // Act and Assert
    Get(
      s"/api/admin/v2/users?id=${samUser.id}&googleSubjectId=${googleUser.googleSubjectId.get}&azureB2CId=${azureUser.azureB2CId.get}"
    ) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[SamUser]].size shouldEqual 3
      responseAs[Set[SamUser]] shouldEqual users.toSet
    }
  }

  it should "forbid non service admin users from accessing user records" in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUser(defaultUser)
      .withAllowedUser(defaultUser)
      .build

    // Act and Assert
    Get(s"/api/admin/v2/users?id=${defaultUserId}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

}
