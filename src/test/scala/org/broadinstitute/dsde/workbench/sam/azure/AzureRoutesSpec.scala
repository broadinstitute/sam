package org.broadinstitute.dsde.workbench.sam.azure

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.testkit.TestDuration
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.sam.TestSupport.configResourceTypes
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes
import org.broadinstitute.dsde.workbench.sam.azure.AzureJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicyMembership, SamResourceTypes, SamUser}
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.mockito.Mockito.when
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.duration._

class AzureRoutesSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with TestSupport with MockitoSugar {
  implicit val timeout = RouteTestTimeout(15.seconds.dilated)

  "POST /api/azure/v1/user/petManagedIdentity" should "successfully create a pet managed identity" in {
    val samRoutes = genSamRoutes()

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
    val samRoutes = genSamRoutes()

    // Non-existent MRG
    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), ManagedResourceGroupName("non-existent-mrg"))
    Post("/api/azure/v1/user/petManagedIdentity", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.NotFound
      contentType shouldEqual ContentTypes.`application/json`
    }
  }

  it should "return 404 if the MRG exists but the user does not have access to the billing profile" in {
    // Don't create spend-profile resource
    val samRoutes = genSamRoutes(createSpendProfile = false)

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
    when(mockCrlService.getManagedAppPlanIds)
      .thenReturn(Seq("some-other-plan", "yet-another-plan"))
    val samRoutes = genSamRoutes(crlService = Some(mockCrlService))

    // User has no access
    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), MockCrlService.mockMrgName)
    Post("/api/azure/v1/user/petManagedIdentity", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.Forbidden
      contentType shouldEqual ContentTypes.`application/json`
    }
  }

  "POST /api/azure/v1/petManagedIdentity/{email}" should "successfully create a pet managed identity for a user" in {
    val samRoutes = genSamRoutes()
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
    val samRoutes = genSamRoutes()
    // Don't add second user to the spend-profile resource
    val newUser = createSecondUser(samRoutes, addToSpendProfile = false)

    // User has no access
    val request = GetOrCreatePetManagedIdentityRequest(TenantId("some-tenant"), SubscriptionId("some-sub"), MockCrlService.mockMrgName)
    Post(s"/api/azure/v1/petManagedIdentity/${newUser.email.value}", request) ~> samRoutes.route ~> check {
      handled shouldBe true
      status shouldEqual StatusCodes.NotFound
      contentType shouldEqual ContentTypes.`application/json`
    }
  }

  private def genSamRoutes(createSpendProfile: Boolean = true, createAzurePolicy: Boolean = true, crlService: Option[CrlService] = None): TestSamRoutes = {
    val resourceTypes = configResourceTypes.view.filterKeys(k => k == SamResourceTypes.spendProfile || k == SamResourceTypes.cloudExtensionName)
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
    Post(s"/api/resources/v2/${SamResourceTypes.cloudExtensionName.value}/${AzureExtensions.resourceId}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    // Create azure policy on cloud-extension resource
    if (createAzurePolicy) {
      val cloudExtensionMembers = AccessPolicyMembership(Set(samRoutes.user.email), Set(AzureExtensions.getPetManagedIdentityAction), Set.empty, None)
      Put(
        s"/api/resources/v2/${SamResourceTypes.cloudExtensionName.value}/${AzureExtensions.resourceId.value}/policies/azure",
        cloudExtensionMembers
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.Created
      }
    }

    samRoutes
  }

  private def createSecondUser(samRoutes: TestSamRoutes, addToSpendProfile: Boolean = true): SamUser = {
    val newUser = Generator.genWorkbenchUserGoogle.sample.get
    // wait for Future to complete by converting to IO
    IO.fromFuture(IO(samRoutes.userService.createUser(newUser, samRequestContext))).unsafeRunSync()
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
