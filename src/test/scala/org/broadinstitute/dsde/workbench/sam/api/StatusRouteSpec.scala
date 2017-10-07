package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.health.{HealthMonitor, StatusCheckResponse}
import org.broadinstitute.dsde.workbench.health.Subsystems.{GoogleGroups, OpenDJ}
import org.scalatest.{FlatSpec, Matchers}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.health.StatusJsonSupport._
import org.broadinstitute.dsde.workbench.model.{WorkbenchUserEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.UserInfo
import org.broadinstitute.dsde.workbench.sam.openam.MockAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.service.{ResourceService, StatusService, UserService}

class StatusRouteSpec extends FlatSpec with Matchers with ScalatestRouteTest with TestSupport {

  "StatusRoute" should "give 200 for ok" in {
    val samRoutes = TestSamRoutes(Map.empty)
    Get("/status") ~> samRoutes.route ~> check {
      responseAs[StatusCheckResponse] shouldEqual StatusCheckResponse(true, Map(OpenDJ -> HealthMonitor.OkStatus, GoogleGroups -> HealthMonitor.OkStatus))
      status shouldEqual StatusCodes.OK
    }

  }

  it should "give 500 for not ok" in {
    val directoryDAO = new MockDirectoryDAO()
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val policyDAO = new MockAccessPolicyDAO()

    val mockResourceService = new ResourceService(policyDAO, directoryDAO, "example.com")
    val mockUserService = new UserService(directoryDAO, googleDirectoryDAO, "dev.test.firecloud.org")
    val mockStatusService = new StatusService(directoryDAO, googleDirectoryDAO)

    val samRoutes = new TestSamRoutes(Map.empty, mockResourceService, mockUserService, mockStatusService, UserInfo("", WorkbenchUserId(""), WorkbenchUserEmail(""), 0))

    Get("/status") ~> samRoutes.route ~> check {
      responseAs[StatusCheckResponse] shouldEqual StatusCheckResponse(false, Map(OpenDJ -> HealthMonitor.failedStatus("could not find group All_Users in opendj"), GoogleGroups -> HealthMonitor.UnknownStatus))
      status shouldEqual StatusCodes.InternalServerError
    }
  }
}
