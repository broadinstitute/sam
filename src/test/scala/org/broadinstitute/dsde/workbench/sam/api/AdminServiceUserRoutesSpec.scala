package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json.DefaultJsonProtocol.immSetFormat

class AdminServiceUserRoutesSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with MockitoSugar with TestSupport {
  val defaultUser: SamUser = Generator.genWorkbenchUserGoogle.sample.get
  val defaultUserId: WorkbenchUserId = defaultUser.id
  val defaultUserEmail: WorkbenchEmail = defaultUser.email
  val adminGroupEmail: WorkbenchEmail = Generator.genFirecloudEmail.sample.get
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))
  private val badUserId = WorkbenchUserId(s"-$defaultUserId")
  private val newUserEmail = WorkbenchEmail(s"XXX${defaultUserEmail}XXX")

  "GET /admin/v2/users" should "get the matching user records when provided with matching query parameters" in {
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

  it should "forbid non service admin users from " in {
    // Arrange
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUser(defaultUser)
      .build

    // Act and Assert
    Get(s"/api/admin/v2/users?id=${defaultUserId}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }
}
