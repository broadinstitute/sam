package org.broadinstitute.dsde.workbench.sam.azure

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.testkit.TestDuration
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport.configResourceTypes
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes
import org.broadinstitute.dsde.workbench.sam.azure.AzureJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamResourceTypes
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

class AzureRoutesSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with TestSupport with MockitoSugar {
  implicit val timeout = RouteTestTimeout(15.seconds.dilated)
  private val spendProfileResourceType = configResourceTypes.getOrElse(SamResourceTypes.spendProfile,
    throw new RuntimeException("Failed to load spend-profile resource type from reference.conf"))

  "POST /api/azure/v1/user/petManagedIdentity" should "successfully create a pet managed identity" in {
    val samRoutes = TestSamRoutes(Map(SamResourceTypes.spendProfile -> spendProfileResourceType))

    // Create mock spend-profile resource
    Post(s"/api/resources/v2/${MockCrlService.mockSamSpendProfileResource.resourceTypeName.value}/${MockCrlService.mockSamSpendProfileResource.resourceId.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    // Create a pet managed identity, should return 201 created
    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), MockCrlService.mockMrgName)
    Post("/api/azure/v1/user/petManagedIdentity", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.Created
      contentType shouldEqual ContentTypes.`application/json`
    }

    // Create again, should return 200
    Post("/api/azure/v1/user/petManagedIdentity", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.OK
      contentType shouldEqual ContentTypes.`application/json`
    }
  }

  it should "return 404 if the MRG does not exist" in {
    val samRoutes = TestSamRoutes(Map(SamResourceTypes.spendProfile -> spendProfileResourceType))

    // Non-existent MRG
    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), ManagedResourceGroupName("non-existent-mrg"))
    Post("/api/azure/v1/user/petManagedIdentity", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.NotFound
      contentType shouldEqual ContentTypes.`application/json`
    }
  }

  it should "return 404 if the MRG exists but the user does not have access to the billing profile" in {
    val samRoutes = TestSamRoutes(Map(SamResourceTypes.spendProfile -> spendProfileResourceType))

    // User has no access
    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), MockCrlService.mockMrgName)
    Post("/api/azure/v1/user/petManagedIdentity", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.NotFound
      contentType shouldEqual ContentTypes.`application/json`
    }
  }

  it should "return 403 if user has access to the billing profile but the MRG could not be validated" in {
    // Change the managed app plan
    val mockCrlService = MockCrlService()
    when(mockCrlService.getManagedAppPlanId)
      .thenReturn("some-other-plan")
    val samRoutes = TestSamRoutes(Map(SamResourceTypes.spendProfile -> spendProfileResourceType), crlService = Some(mockCrlService))

    // Create mock spend-profile resource
    Post(s"/api/resources/v2/${MockCrlService.mockSamSpendProfileResource.resourceTypeName.value}/${MockCrlService.mockSamSpendProfileResource.resourceId.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), MockCrlService.mockMrgName)
    Post("/api/azure/v1/user/petManagedIdentity", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.Forbidden
      contentType shouldEqual ContentTypes.`application/json`
    }
  }

}
