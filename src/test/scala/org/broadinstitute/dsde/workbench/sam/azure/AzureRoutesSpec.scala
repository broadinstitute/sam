package org.broadinstitute.dsde.workbench.sam.azure

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.testkit.TestDuration
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.sam.TestSupport.configResourceTypes
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes
import org.broadinstitute.dsde.workbench.sam.azure.AzureJsonSupport._
import org.broadinstitute.dsde.workbench.sam.azure.MockCrlService.mockSamSpendProfileResource
import org.broadinstitute.dsde.workbench.sam.config.{AzureServicesConfig, ManagedAppPlan}
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamResourceTypes
import org.broadinstitute.dsde.workbench.sam.model.api._
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.mockito.scalatest.MockitoSugar

import scala.concurrent.duration._

class AzureRoutesSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with TestSupport with MockitoSugar {
  implicit val timeout = RouteTestTimeout(15.seconds.dilated)

  "POST /api/azure/v1/user/petManagedIdentity" should "successfully create a pet managed identity using MRG in db" in {
    val samRoutes = genSamRoutes()

    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), MockCrlService.mockMrgName)
    Post(
      s"/api/azure/v1/billingProfile/${mockSamSpendProfileResource.resourceId.value}/managedResourceGroup",
      request.toManagedResourceGroupCoordinates
    ) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.Created
    }

    // Create a pet managed identity, should return 201 created
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

  it should "successfully create a pet managed identity using MRG Azure tag for backwards compatibility" in {
    val samRoutes = genSamRoutes(crlService = Option(MockCrlService(includeBillingProfileTag = true)))

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

  it should "return 403 if the MRG does not exist" in {
    val samRoutes = genSamRoutes()

    // Non-existent MRG
    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), ManagedResourceGroupName("non-existent-mrg"))
    Post("/api/azure/v1/user/petManagedIdentity", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.Forbidden
      contentType shouldEqual ContentTypes.`application/json`
    }
  }

  it should "return 403 if the MRG exists but the user does not have access to the billing profile" in {
    // Don't create spend-profile resource
    val samRoutes = genSamRoutes(createSpendProfile = false, crlService = Option(MockCrlService(includeBillingProfileTag = true)))

    // User has no access
    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), MockCrlService.mockMrgName)
    Post("/api/azure/v1/user/petManagedIdentity", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.Forbidden
      contentType shouldEqual ContentTypes.`application/json`
    }
  }

  it should "return 403 if user has access to the billing profile but the MRG could not be validated" in {
    // Change the managed app plan
    val mockCrlService = MockCrlService()
    val mockAzureServicesConfig = mock[AzureServicesConfig]

    when(mockAzureServicesConfig.managedAppPlans)
      .thenReturn(Seq(ManagedAppPlan("some-other-plan", "publisher", "auth"), ManagedAppPlan("yet-another-plan", "publisher", "auth")))
    val samRoutes = genSamRoutes(crlService = Some(mockCrlService))

    // User has no access
    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), MockCrlService.mockMrgName)
    Post("/api/azure/v1/user/petManagedIdentity", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.Forbidden
      contentType shouldEqual ContentTypes.`application/json`
    }
  }

  "POST /api/azure/v1/petManagedIdentity/{email}" should "successfully create a pet managed identity for a user using MRG in db" in {
    val samRoutes = genSamRoutes()
    val newUser = createSecondUser(samRoutes)

    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), MockCrlService.mockMrgName)
    Post(
      s"/api/azure/v1/billingProfile/${mockSamSpendProfileResource.resourceId.value}/managedResourceGroup",
      request.toManagedResourceGroupCoordinates
    ) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.Created
    }

    // Create a pet managed identity, should return 201 created
    Post(s"/api/azure/v1/petManagedIdentity/${newUser.email.value}", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.Created
      contentType shouldEqual ContentTypes.`application/json`
    }

    // Create again, should return 200
    Post(s"/api/azure/v1/petManagedIdentity/${newUser.email.value}", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.OK
      contentType shouldEqual ContentTypes.`application/json`
    }
  }

  it should "successfully create a pet managed identity for a user using MRG Azure tag for backwards compatibility" in {
    val samRoutes = genSamRoutes(crlService = Option(MockCrlService(includeBillingProfileTag = true)))
    val newUser = createSecondUser(samRoutes)

    // Create a pet managed identity for the second user, should return 201 created
    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), MockCrlService.mockMrgName)
    Post(s"/api/azure/v1/petManagedIdentity/${newUser.email.value}", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.Created
      contentType shouldEqual ContentTypes.`application/json`
    }

    // Create again, should return 200
    Post(s"/api/azure/v1/petManagedIdentity/${newUser.email.value}", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.OK
      contentType shouldEqual ContentTypes.`application/json`
    }
  }

  it should "return 404 for a non-existent user" in {
    val samRoutes = genSamRoutes()

    // Non-existent user
    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), MockCrlService.mockMrgName)
    Post(s"/api/azure/v1/petManagedIdentity/fake@fake.com", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.NotFound
      contentType shouldEqual ContentTypes.`application/json`
    }
  }

  it should "return 403 if the calling user does not have access to the Azure cloud-extension" in {
    // Don't create an cloud-extensions/azure policy
    val samRoutes = genSamRoutes(createAzurePolicy = false)
    val newUser = createSecondUser(samRoutes)

    // User has no access
    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), MockCrlService.mockMrgName)
    Post(s"/api/azure/v1/petManagedIdentity/${newUser.email.value}", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.Forbidden
      contentType shouldEqual ContentTypes.`application/json`
    }
  }

  it should "return 403 if the user does not have access to the billing profile" in {
    val samRoutes = genSamRoutes(crlService = Option(MockCrlService(includeBillingProfileTag = true)))
    // Don't add second user to the spend-profile resource
    val newUser = createSecondUser(samRoutes, addToSpendProfile = false)

    // User has no access
    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), MockCrlService.mockMrgName)
    Post(s"/api/azure/v1/petManagedIdentity/${newUser.email.value}", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.Forbidden
      contentType shouldEqual ContentTypes.`application/json`
    }
  }

  "POST /api/azure/v1/billingProfile/{billingProfileId}/managedResourceGroup" should "successfully create a managed resource group" in {
    val samRoutes = genSamRoutes()
    val request = ManagedResourceGroupCoordinates(TenantId("some-tenant"), SubscriptionId("some-sub"), MockCrlService.mockMrgName)
    Post(
      s"/api/azure/v1/billingProfile/${MockCrlService.mockSamSpendProfileResource.resourceId.value}/managedResourceGroup",
      request
    ) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.Created
    }
  }

  it should "return 404 if the user does not have access to the billing profile" in {
    val samRoutes = genSamRoutes(createSpendProfile = false)
    val request = ManagedResourceGroupCoordinates(TenantId("some-tenant"), SubscriptionId("some-sub"), MockCrlService.mockMrgName)
    Post(
      s"/api/azure/v1/billingProfile/${MockCrlService.mockSamSpendProfileResource.resourceId.value}/managedResourceGroup",
      request
    ) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.NotFound
      contentType shouldEqual ContentTypes.`application/json`
    }
  }

  "DELETE /api/azure/v1/billingProfile/{billingProfileId}/managedResourceGroup" should "successfully delete a managed resource group" in {
    val samRoutes = genSamRoutes(createMrg = true)

    Delete(
      s"/api/azure/v1/billingProfile/${MockCrlService.mockSamSpendProfileResource.resourceId.value}/managedResourceGroup"
    ) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "return 404 if the MRG does not exist" in {
    val samRoutes = genSamRoutes()
    Delete(
      s"/api/azure/v1/billingProfile/${MockCrlService.mockSamSpendProfileResource.resourceId.value}/managedResourceGroup"
    ) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "return 404 when a user does not have access to the parent spend profile" in {
    val samRoutes = genSamRoutes(createMrg = true)

    Delete(
      s"/api/resources/v2/${SamResourceTypes.spendProfile.value}/${MockCrlService.mockSamSpendProfileResource.resourceId.value}/policies/owner/memberEmails/${samRoutes.user.email.value}"
    ) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Delete(
      s"/api/azure/v1/billingProfile/${MockCrlService.mockSamSpendProfileResource.resourceId.value}/managedResourceGroup"
    ) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.NotFound
    }
  }

  private def genSamRoutes(
      createSpendProfile: Boolean = true,
      createAzurePolicy: Boolean = true,
      crlService: Option[CrlService] = None,
      createMrg: Boolean = false
  ): TestSamRoutes = {
    val resourceTypes = configResourceTypes.view.filterKeys(k => k == SamResourceTypes.spendProfile || k == CloudExtensions.resourceTypeName)
    val samRoutes = TestSamRoutes(resourceTypes.toMap, crlService = crlService)

    // Create mock spend-profile resource
    if (createSpendProfile) {
      Post(
        s"/api/resources/v2/${MockCrlService.mockSamSpendProfileResource.resourceTypeName.value}/${MockCrlService.mockSamSpendProfileResource.resourceId.value}"
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.NoContent
      }
    }

    // Create Azure cloud-extension resource
    Post(s"/api/resources/v2/${CloudExtensions.resourceTypeName.value}/${AzureExtensions.resourceId}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    // Create azure policy on cloud-extension resource
    if (createAzurePolicy) {
      val cloudExtensionMembers = AccessPolicyMembershipResponse(Set(samRoutes.user.email), Set(AzureExtensions.getPetManagedIdentityAction), Set.empty, None)
      Put(
        s"/api/resources/v2/${CloudExtensions.resourceTypeName.value}/${AzureExtensions.resourceId.value}/policies/azure",
        cloudExtensionMembers
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.Created
      }
    }

    if (createMrg) {
      val request = ManagedResourceGroupCoordinates(TenantId("some-tenant"), SubscriptionId("some-sub"), MockCrlService.mockMrgName)
      Post(
        s"/api/azure/v1/billingProfile/${MockCrlService.mockSamSpendProfileResource.resourceId.value}/managedResourceGroup",
        request
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.Created
      }
    }

    samRoutes
  }

  private def createSecondUser(samRoutes: TestSamRoutes, addToSpendProfile: Boolean = true): SamUser = {
    val newUser = Generator.genWorkbenchUserGoogle.sample.get
    // wait for Future to complete by converting to IO
    samRoutes.userService.createUser(newUser, samRequestContext).unsafeRunSync()
    if (addToSpendProfile) {
      Put(
        s"/api/resources/v2/${SamResourceTypes.spendProfile.value}/${MockCrlService.mockSamSpendProfileResource.resourceId.value}/policies/owner/memberEmails/${newUser.email.value}"
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.NoContent
      }
    }
    newUser
  }

}
