package org.broadinstitute.dsde.workbench.sam.azure

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport.configResourceTypes
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes
import org.broadinstitute.dsde.workbench.sam.azure.AzureJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamResourceTypes
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AzureRoutesSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with TestSupport {
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
    }

    // Create again, should return 200
    Post("/api/azure/v1/user/petManagedIdentity", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.OK
    }
  }

  it should "return 404 if the MRG does not exist" in {
    val samRoutes = TestSamRoutes(Map(SamResourceTypes.spendProfile -> spendProfileResourceType))

    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), ManagedResourceGroupName("non-existent-mrg"))
    Post("/api/azure/v1/user/petManagedIdentity", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "return 404 if the MRG exists but the user does not have access to the billing profile" in {
    val samRoutes = TestSamRoutes(Map(SamResourceTypes.spendProfile -> spendProfileResourceType))

    // Create a pet managed identity, should return 201 created
    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), MockCrlService.mockMrgName)
    Post("/api/azure/v1/user/petManagedIdentity", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.NotFound
    }
  }

}
