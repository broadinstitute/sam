package org.broadinstitute.dsde.workbench.sam.azure

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.testkit.TestDuration
import org.broadinstitute.dsde.workbench.model.ErrorReport
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport.azureServicesConfig
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes
import org.broadinstitute.dsde.workbench.sam.azure.AzureJsonSupport._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class AzureRoutesSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with TestSupport {

  implicit val timeout = RouteTestTimeout(15.seconds.dilated)

  "POST /api/azure/v1/user/petManagedIdentity" should "be handled" in {
    assume(azureServicesConfig.isDefined, "-- skipping Azure test")

    val samRoutes = TestSamRoutes(Map.empty)
    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), ManagedResourceGroupName("some-mrg"))

    Post("/api/azure/v1/user/petManagedIdentity", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.NotFound
      responseAs[ErrorReport].message shouldEqual "Managed resource group some-mrg not found"
    }
  }

}
