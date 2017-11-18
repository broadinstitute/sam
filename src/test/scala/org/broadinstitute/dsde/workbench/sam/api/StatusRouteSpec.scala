package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.util.health.{HealthMonitor, StatusCheckResponse}
import org.broadinstitute.dsde.workbench.util.health.Subsystems.{GoogleGroups, OpenDJ}
import org.scalatest.{FlatSpec, Matchers}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.broadinstitute.dsde.workbench.google.mock.{MockGoogleDirectoryDAO, MockGoogleIamDAO}
import org.broadinstitute.dsde.workbench.google.model.GoogleProject
import org.broadinstitute.dsde.workbench.util.health.StatusJsonSupport._
import org.broadinstitute.dsde.workbench.model.{WorkbenchUserEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.PetServiceAccountConfig
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.UserInfo
import org.broadinstitute.dsde.workbench.sam.openam.MockAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.service.{NoExtensions, ResourceService, StatusService, UserService}
import org.scalatest.concurrent.Eventually._

import scala.concurrent.duration._

class StatusRouteSpec extends FlatSpec with Matchers with ScalatestRouteTest with TestSupport {

  "GET /status" should "give 200 for ok" in {
    val samRoutes = TestSamRoutes(Map.empty)
    implicit val patienceConfig = PatienceConfig(timeout = 1 second)
    eventually {
      Get("/status") ~> samRoutes.route ~> check {
        responseAs[StatusCheckResponse] shouldEqual StatusCheckResponse(true, Map(OpenDJ -> HealthMonitor.OkStatus))
        status shouldEqual StatusCodes.OK
      }
    }
  }

  it should "give 500 for not ok" in {
    val directoryDAO = new MockDirectoryDAO()
    val policyDAO = new MockAccessPolicyDAO()

    val mockResourceService = new ResourceService(Map.empty, policyDAO, directoryDAO, NoExtensions, "example.com")
    val mockUserService = new UserService(directoryDAO, NoExtensions, "dev.test.firecloud.org")
    val mockStatusService = new StatusService(directoryDAO, NoExtensions)

    val samRoutes = new TestSamRoutes(mockResourceService, mockUserService, mockStatusService, UserInfo("", WorkbenchUserId(""), WorkbenchUserEmail(""), 0))

    Get("/status") ~> samRoutes.route ~> check {
      responseAs[StatusCheckResponse].ok shouldEqual false
      status shouldEqual StatusCodes.InternalServerError
    }
  }
}
