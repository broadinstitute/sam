package org.broadinstitute.dsde.workbench.sam.azure

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.azure.core.http.rest.PagedIterable
import com.azure.core.management.Region
import com.azure.core.util.Context
import com.azure.resourcemanager.managedapplications.ApplicationManager
import com.azure.resourcemanager.managedapplications.models.{Application, Applications, Plan}
import com.azure.resourcemanager.msi.MsiManager
import com.azure.resourcemanager.msi.models.Identity.DefinitionStages
import com.azure.resourcemanager.msi.models.{Identities, Identity}
import com.azure.resourcemanager.resources.ResourceManager
import com.azure.resourcemanager.resources.models.{ResourceGroup, ResourceGroups}
import org.broadinstitute.dsde.workbench.sam.TestSupport.appConfig
import org.broadinstitute.dsde.workbench.sam.config.ManagedAppPlan
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AzureManagedResourceGroupDAO, DirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedResourceId, ResourceAction, ResourceId, ResourceTypeName}
import org.broadinstitute.dsde.workbench.sam.{Generator, PropertyBasedTesting, TestSupport}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID

class AzureServiceUnitSpec extends AnyFreeSpec with Matchers with ScalaFutures with TestSupport with MockitoSugar with PropertyBasedTesting {

  private val dummyUser = Generator.genWorkbenchUserBoth.sample.get

  "AzureService" - {
    "Action Managed Identities" - {
      "create an Action Managed Identity" in {
        // Arrange
        val mockCrlService = mock[CrlService]
        val mockDirectoryDAO = mock[DirectoryDAO]
        val mockAzureManagedResourceGroupDAO = mock[AzureManagedResourceGroupDAO]
        val mockMsiManager = mock[MsiManager]
        val mockApplicationManager = mock[ApplicationManager]
        val mockApplications = mock[Applications]
        val mockPagedResponse = mock[PagedIterable[Application]]
        val mockApplication = mock[Application]
        val mockPlan = mock[Plan]
        val mockResourceManager = mock[ResourceManager]
        val mockResourceGroups = mock[ResourceGroups]
        val mockResourceGroup = mock[ResourceGroup]
        val mockIdentities = mock[Identities]
        val mockIdentitiesBlank = mock[DefinitionStages.Blank]
        val mockIdentityWithGroup = mock[DefinitionStages.WithGroup]
        val mockIdentityWithCreate = mock[DefinitionStages.WithCreate]
        val mockIdentity = mock[Identity]
        val azureServicesConfig = appConfig.azureServicesConfig
        val azureService = new AzureService(azureServicesConfig, mockCrlService, mockDirectoryDAO, mockAzureManagedResourceGroupDAO)

        val testMrgCoordinates = ManagedResourceGroupCoordinates(
          TenantId(UUID.randomUUID().toString),
          SubscriptionId(UUID.randomUUID().toString),
          ManagedResourceGroupName(UUID.randomUUID().toString)
        )
        val testBillingProfileId = BillingProfileId(UUID.randomUUID().toString)
        val testAction = ResourceAction("testAction")
        val testResource = FullyQualifiedResourceId(ResourceTypeName("testResourceType"), ResourceId("testResource"))
        val testActionManagedIdentityId = ActionManagedIdentityId(testResource, testAction, testBillingProfileId)
        val testDisplayName = azureService.toManagedIdentityNameFromAmiId(testActionManagedIdentityId)
        val testObjectId = ManagedIdentityObjectId(UUID.randomUUID().toString)
        val testActionManagedIdentity = ActionManagedIdentity(testActionManagedIdentityId, testObjectId, testDisplayName, testMrgCoordinates)
        val testManagedAppPlan = ManagedAppPlan("testPlan", "testPublisher", UUID.randomUUID().toString)
        val testSamRequestContext = samRequestContext.copy(samUser = Some(dummyUser))
        val testMrgId = UUID.randomUUID().toString

        when(mockDirectoryDAO.loadActionManagedIdentity(testActionManagedIdentityId, testSamRequestContext)).thenReturn(IO.pure(None))
        when(mockAzureManagedResourceGroupDAO.getManagedResourceGroupByBillingProfileId(testBillingProfileId, testSamRequestContext))
          .thenReturn(IO.pure(Some(ManagedResourceGroup(testMrgCoordinates, testBillingProfileId))))
        when(mockCrlService.buildMsiManager(testMrgCoordinates.tenantId, testMrgCoordinates.subscriptionId)).thenReturn(IO.pure(mockMsiManager))
        when(mockCrlService.buildApplicationManager(testMrgCoordinates.tenantId, testMrgCoordinates.subscriptionId)).thenReturn(IO.pure(mockApplicationManager))
        when(mockCrlService.getManagedAppPlans).thenReturn(Seq(testManagedAppPlan))
        when(mockApplicationManager.applications()).thenReturn(mockApplications)
        when(mockApplications.list()).thenReturn(mockPagedResponse)
        when(mockPagedResponse.iterator()).thenReturn(java.util.List.of(mockApplication).iterator())
        when(mockApplication.plan()).thenReturn(mockPlan)
        when(mockApplication.managedResourceGroupId()).thenReturn(testMrgId)
        when(mockApplication.parameters()).thenReturn(java.util.Map.of(testManagedAppPlan.authorizedUserKey, java.util.Map.of("value", dummyUser.email.value)))
        when(mockPlan.name()).thenReturn(testManagedAppPlan.name)
        when(mockPlan.publisher()).thenReturn(testManagedAppPlan.publisher)
        when(mockCrlService.buildResourceManager(testMrgCoordinates.tenantId, testMrgCoordinates.subscriptionId)).thenReturn(IO.pure(mockResourceManager))
        when(mockResourceManager.resourceGroups()).thenReturn(mockResourceGroups)
        when(mockResourceGroups.getByName(testMrgCoordinates.managedResourceGroupName.value)).thenReturn(mockResourceGroup)
        when(mockResourceGroup.region()).thenReturn(Region.US_EAST)
        when(mockResourceGroup.id()).thenReturn(testMrgId)
        when(mockMsiManager.identities()).thenReturn(mockIdentities)
        when(mockIdentities.define(testDisplayName.value)).thenReturn(mockIdentitiesBlank)
        when(mockIdentitiesBlank.withRegion(Region.US_EAST)).thenReturn(mockIdentityWithGroup)
        when(mockIdentityWithGroup.withExistingResourceGroup(testMrgCoordinates.managedResourceGroupName.value)).thenReturn(mockIdentityWithCreate)
        when(mockIdentityWithCreate.withTags(any[java.util.Map[String, String]])).thenReturn(mockIdentityWithCreate)
        when(mockIdentityWithCreate.create(any[Context])).thenReturn(mockIdentity)
        when(mockIdentity.id()).thenReturn(testObjectId.value)
        when(mockIdentity.name()).thenReturn(testDisplayName.value)
        when(mockDirectoryDAO.createActionManagedIdentity(testActionManagedIdentity, testSamRequestContext)).thenReturn(IO.pure(testActionManagedIdentity))

        // Act
        val (ami, created) =
          azureService.getOrCreateActionManagedIdentity(testResource, testAction, testBillingProfileId, testSamRequestContext).unsafeRunSync()

        // Assert
        ami should be(testActionManagedIdentity)
        created should be(true)
      }

      "retrieve an existing Action Managed Identity" in {
        // Arrange
        val mockCrlService = mock[CrlService]
        val mockDirectoryDAO = mock[DirectoryDAO]
        val mockAzureManagedResourceGroupDAO = mock[AzureManagedResourceGroupDAO]

        val azureService = new AzureService(appConfig.azureServicesConfig, mockCrlService, mockDirectoryDAO, mockAzureManagedResourceGroupDAO)

        val testMrgCoordinates = ManagedResourceGroupCoordinates(
          TenantId(UUID.randomUUID().toString),
          SubscriptionId(UUID.randomUUID().toString),
          ManagedResourceGroupName(UUID.randomUUID().toString)
        )
        val testBillingProfileId = BillingProfileId(UUID.randomUUID().toString)
        val testAction = ResourceAction("testAction")
        val testResource = FullyQualifiedResourceId(ResourceTypeName("testResourceType"), ResourceId("testResource"))
        val testActionManagedIdentityId = ActionManagedIdentityId(testResource, testAction, testBillingProfileId)
        val testDisplayName = azureService.toManagedIdentityNameFromAmiId(testActionManagedIdentityId)
        val testObjectId = ManagedIdentityObjectId(UUID.randomUUID().toString)
        val testActionManagedIdentity = ActionManagedIdentity(testActionManagedIdentityId, testObjectId, testDisplayName, testMrgCoordinates)

        when(mockDirectoryDAO.loadActionManagedIdentity(testActionManagedIdentityId, samRequestContext)).thenReturn(IO.pure(Option(testActionManagedIdentity)))

        // Act
        val (ami, created) =
          azureService.getOrCreateActionManagedIdentity(testResource, testAction, testBillingProfileId, samRequestContext).unsafeRunSync()

        // Assert
        ami should be(testActionManagedIdentity)
        created should be(false)
        verify(mockCrlService, never).buildMsiManager(testMrgCoordinates.tenantId, testMrgCoordinates.subscriptionId)
      }

      "delete an existing Action Managed Identity" in {
        // Arrange
        val mockCrlService = mock[CrlService]
        val mockDirectoryDAO = mock[DirectoryDAO]
        val mockAzureManagedResourceGroupDAO = mock[AzureManagedResourceGroupDAO]
        val mockMsiManager = mock[MsiManager]
        val mockIdentities = mock[Identities]

        val azureService = new AzureService(appConfig.azureServicesConfig, mockCrlService, mockDirectoryDAO, mockAzureManagedResourceGroupDAO)

        val testMrgCoordinates = ManagedResourceGroupCoordinates(
          TenantId(UUID.randomUUID().toString),
          SubscriptionId(UUID.randomUUID().toString),
          ManagedResourceGroupName(UUID.randomUUID().toString)
        )
        val testBillingProfileId = BillingProfileId(UUID.randomUUID().toString)
        val testAction = ResourceAction("testAction")
        val testResource = FullyQualifiedResourceId(ResourceTypeName("testResourceType"), ResourceId("testResource"))
        val testActionManagedIdentityId = ActionManagedIdentityId(testResource, testAction, testBillingProfileId)
        val testDisplayName = azureService.toManagedIdentityNameFromAmiId(testActionManagedIdentityId)
        val testObjectId = ManagedIdentityObjectId(UUID.randomUUID().toString)
        val testActionManagedIdentity = ActionManagedIdentity(testActionManagedIdentityId, testObjectId, testDisplayName, testMrgCoordinates)

        when(mockDirectoryDAO.loadActionManagedIdentity(testActionManagedIdentityId, samRequestContext)).thenReturn(IO.pure(Option(testActionManagedIdentity)))
        when(mockCrlService.buildMsiManager(testMrgCoordinates.tenantId, testMrgCoordinates.subscriptionId)).thenReturn(IO.pure(mockMsiManager))
        when(mockMsiManager.identities()).thenReturn(mockIdentities)
        doNothing.when(mockIdentities).deleteById(testObjectId.value)
        when(mockDirectoryDAO.deleteActionManagedIdentity(testActionManagedIdentityId, samRequestContext)).thenReturn(IO.pure(()))

        // Act & Assert
        azureService.deleteActionManagedIdentity(testActionManagedIdentityId, samRequestContext).unsafeRunSync()
      }
    }

    "Managed Resource Groups" - {
      "delete action managed identities when deleting managed resource group" in {
        // Arrange
        val mockCrlService = mock[CrlService]
        val mockDirectoryDAO = mock[DirectoryDAO]
        val mockAzureManagedResourceGroupDAO = mock[AzureManagedResourceGroupDAO]
        val mockMsiManager = mock[MsiManager]
        val mockIdentities = mock[Identities]

        val azureService = new AzureService(appConfig.azureServicesConfig, mockCrlService, mockDirectoryDAO, mockAzureManagedResourceGroupDAO)

        val testMrgCoordinates = ManagedResourceGroupCoordinates(
          TenantId(UUID.randomUUID().toString),
          SubscriptionId(UUID.randomUUID().toString),
          ManagedResourceGroupName(UUID.randomUUID().toString)
        )
        val testBillingProfileId = BillingProfileId(UUID.randomUUID().toString)
        val testManagedResourceGroup = ManagedResourceGroup(testMrgCoordinates, testBillingProfileId)
        val testAction = ResourceAction("testAction")
        val testResource = FullyQualifiedResourceId(ResourceTypeName("testResourceType"), ResourceId("testResource"))
        val testActionManagedIdentityId = ActionManagedIdentityId(testResource, testAction, testBillingProfileId)
        val testDisplayName = azureService.toManagedIdentityNameFromAmiId(testActionManagedIdentityId)
        val testObjectId = ManagedIdentityObjectId(UUID.randomUUID().toString)
        val testActionManagedIdentity = ActionManagedIdentity(testActionManagedIdentityId, testObjectId, testDisplayName, testMrgCoordinates)

        val testAction2 = ResourceAction("testAction2")
        val testDisplayName2 = azureService.toManagedIdentityNameFromAmiId(testActionManagedIdentityId)
        val testObjectId2 = ManagedIdentityObjectId(UUID.randomUUID().toString)
        val testActionManagedIdentityId2 = ActionManagedIdentityId(testResource, testAction2, testBillingProfileId)
        val testActionManagedIdentity2 = ActionManagedIdentity(testActionManagedIdentityId2, testObjectId2, testDisplayName2, testMrgCoordinates)

        when(mockDirectoryDAO.getAllActionManagedIdentitiesForBillingProfile(testActionManagedIdentityId.billingProfileId, samRequestContext))
          .thenReturn(IO.pure(Seq(testActionManagedIdentity, testActionManagedIdentity2)))
        when(mockAzureManagedResourceGroupDAO.getManagedResourceGroupByBillingProfileId(testBillingProfileId, samRequestContext))
          .thenReturn(IO.pure(Some(testManagedResourceGroup)))
        when(mockCrlService.buildMsiManager(testMrgCoordinates.tenantId, testMrgCoordinates.subscriptionId)).thenReturn(IO.pure(mockMsiManager))
        when(mockMsiManager.identities()).thenReturn(mockIdentities)
        doNothing.when(mockIdentities).deleteById(testObjectId.value)
        doNothing.when(mockIdentities).deleteById(testObjectId2.value)
        when(mockDirectoryDAO.loadActionManagedIdentity(testActionManagedIdentityId, samRequestContext)).thenReturn(IO.pure(Some(testActionManagedIdentity)))
        when(mockDirectoryDAO.loadActionManagedIdentity(testActionManagedIdentityId2, samRequestContext)).thenReturn(IO.pure(Some(testActionManagedIdentity2)))
        when(mockDirectoryDAO.deleteActionManagedIdentity(testActionManagedIdentityId, samRequestContext)).thenReturn(IO.pure(()))
        when(mockDirectoryDAO.deleteActionManagedIdentity(testActionManagedIdentityId2, samRequestContext)).thenReturn(IO.pure(()))
        when(mockAzureManagedResourceGroupDAO.deleteManagedResourceGroup(testBillingProfileId, samRequestContext)).thenReturn(IO.pure(1))

        // Act & Assert
        azureService.deleteManagedResourceGroup(testBillingProfileId, samRequestContext).unsafeRunSync()
      }
    }
  }
}
