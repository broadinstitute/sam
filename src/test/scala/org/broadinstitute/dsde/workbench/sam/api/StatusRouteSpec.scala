package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import org.broadinstitute.dsde.workbench.sam.TestSupport.googleServicesConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.{MockAccessPolicyDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.{Generator, RetryableAnyFlatSpec, TestSupport}
import org.broadinstitute.dsde.workbench.util.health.StatusJsonSupport._
import org.broadinstitute.dsde.workbench.util.health.Subsystems.Database
import org.broadinstitute.dsde.workbench.util.health.{HealthMonitor, StatusCheckResponse}
import org.mockito.ArgumentMatchers.{any, anyLong, anyString}
import org.mockito.Mockito.when
import org.scalatest.concurrent.Eventually._
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

class StatusRouteSpec extends RetryableAnyFlatSpec with Matchers with ScalatestRouteTest with TestSupport with MockitoSugar {

  "GET /version" should "give 200 for ok" in {
    val samRoutes = TestSamRoutes(Map.empty)
    implicit val patienceConfig = PatienceConfig(timeout = 1 second)
    eventually {
      Get("/version") ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] should include("n/a")
      }
    }
  }

  "GET /status" should "give 200 for ok" in {
    when(openTelemetry.incrementCounter(anyString(), anyLong(), any[Map[String, String]])).thenReturn(IO.unit)
    val samRoutes = TestSamRoutes(Map.empty)
    implicit val patienceConfig = PatienceConfig(timeout = 1 second)
    eventually {
      Get("/status") ~> samRoutes.route ~> check {
        responseAs[StatusCheckResponse] shouldEqual StatusCheckResponse(true, Map(Database -> HealthMonitor.OkStatus))
        status shouldEqual StatusCodes.OK
      }
    }
  }

  it should "give 500 for not ok" in {
    when(openTelemetry.incrementCounter(anyString(), anyLong(), any[Map[String, String]])).thenReturn(IO.unit)
    val directoryDAO = new MockDirectoryDAO(passStatusCheck = false)
    val policyDAO = new MockAccessPolicyDAO(directoryDAO)

    val emailDomain = "example.com"
    val mockResourceService = new ResourceService(Map.empty, null, policyDAO, directoryDAO, NoExtensions, emailDomain, Set.empty)
    val tosService = new TosService(directoryDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig)
    val mockUserService = new UserService(directoryDAO, NoExtensions, Seq.empty, tosService)
    val mockStatusService = new StatusService(directoryDAO, NoExtensions, TestSupport.dbRef)
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, null, Map.empty, policyDAO, directoryDAO, NoExtensions, emailDomain)
    val policyEvaluatorService = PolicyEvaluatorService(emailDomain, Map.empty, policyDAO, directoryDAO)
    val samRoutes = new TestSamRoutes(
      mockResourceService,
      policyEvaluatorService,
      mockUserService,
      mockStatusService,
      mockManagedGroupService,
      Generator.genWorkbenchUserGoogle.sample.get,
      directoryDAO,
      tosService = tosService
    )

    Get("/status") ~> samRoutes.route ~> check {
      responseAs[StatusCheckResponse].ok shouldEqual false
      status shouldEqual StatusCodes.InternalServerError
    }
  }
}
