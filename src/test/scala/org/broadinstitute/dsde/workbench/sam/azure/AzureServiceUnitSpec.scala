package org.broadinstitute.dsde.workbench.sam.azure

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.azure.core.management.Region
import com.azure.core.util.Context
import com.azure.resourcemanager.msi.MsiManager
import com.azure.resourcemanager.msi.models.Identity.DefinitionStages
import com.azure.resourcemanager.msi.models.{Identities, Identity}
import com.azure.resourcemanager.resources.ResourceManager
import com.azure.resourcemanager.resources.models.{ResourceGroup, ResourceGroups}
import org.broadinstitute.dsde.workbench.model.WorkbenchExceptionWithErrorReport
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AzureManagedResourceGroupDAO, DirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedResourceId, ResourceAction, ResourceId, ResourceTypeName}
import org.broadinstitute.dsde.workbench.sam.service.PolicyEvaluatorService
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
        val mockCrlService = mock[CrlService]
        val mockDirectoryDAO = mock[DirectoryDAO]
        val mockAzureManagedResourceGroupDAO = mock[AzureManagedResourceGroupDAO]
        val mockPolicyEvaluatorService = mock[PolicyEvaluatorService]
        val mockMsiManager = mock[MsiManager]
        val mockResourceManager = mock[ResourceManager]
        val mockResourceGroups = mock[ResourceGroups]
        val mockResourceGroup = mock[ResourceGroup]
        val mockIdentities = mock[Identities]
        val mockIdentitiesBlank = mock[DefinitionStages.Blank]
        val mockIdentityWithGroup = mock[DefinitionStages.WithGroup]
        val mockIdentityWithCreate = mock[DefinitionStages.WithCreate]
        val mockIdentity = mock[Identity]

        val azureService = new AzureService(mockCrlService, mockDirectoryDAO, mockAzureManagedResourceGroupDAO, mockPolicyEvaluatorService)

        val testMrgCoordinates = ManagedResourceGroupCoordinates(
          TenantId(UUID.randomUUID().toString),
          SubscriptionId(UUID.randomUUID().toString),
          ManagedResourceGroupName(UUID.randomUUID().toString)
        )
        val testAction = ResourceAction("testAction")
        val testResource = FullyQualifiedResourceId(ResourceTypeName("testResourceType"), ResourceId("testResource"))
        val testActionManagedIdentityId = ActionManagedIdentityId(testResource, testAction, testMrgCoordinates)
        val testDisplayName = azureService.toManagedIdentityNameFromAmiId(testActionManagedIdentityId)
        val testObjectId = ManagedIdentityObjectId(UUID.randomUUID().toString)
        val testActionManagedIdentity = ActionManagedIdentity(testActionManagedIdentityId, testObjectId, testDisplayName)

        when(mockPolicyEvaluatorService.hasPermission(testResource, testAction, dummyUser.id, samRequestContext)).thenReturn(IO.pure(true))
        when(mockDirectoryDAO.loadActionManagedIdentity(testActionManagedIdentityId, samRequestContext)).thenReturn(IO.pure(None))
        when(mockCrlService.buildMsiManager(testMrgCoordinates.tenantId, testMrgCoordinates.subscriptionId)).thenReturn(IO.pure(mockMsiManager))
        when(mockCrlService.buildResourceManager(testMrgCoordinates.tenantId, testMrgCoordinates.subscriptionId)).thenReturn(IO.pure(mockResourceManager))
        when(mockResourceManager.resourceGroups()).thenReturn(mockResourceGroups)
        when(mockResourceGroups.getByName(testMrgCoordinates.managedResourceGroupName.value)).thenReturn(mockResourceGroup)
        when(mockResourceGroup.region()).thenReturn(Region.US_EAST)
        when(mockMsiManager.identities()).thenReturn(mockIdentities)
        when(mockIdentities.define(testDisplayName.value)).thenReturn(mockIdentitiesBlank)
        when(mockIdentitiesBlank.withRegion(Region.US_EAST)).thenReturn(mockIdentityWithGroup)
        when(mockIdentityWithGroup.withExistingResourceGroup(testMrgCoordinates.managedResourceGroupName.value)).thenReturn(mockIdentityWithCreate)
        when(mockIdentityWithCreate.withTags(any[java.util.Map[String, String]])).thenReturn(mockIdentityWithCreate)
        when(mockIdentityWithCreate.create(any[Context])).thenReturn(mockIdentity)
        when(mockIdentity.id()).thenReturn(testObjectId.value)
        when(mockIdentity.name()).thenReturn(testDisplayName.value)
        when(mockDirectoryDAO.createActionManagedIdentity(testActionManagedIdentity, samRequestContext)).thenReturn(IO.pure(testActionManagedIdentity))

        val (ami, created) =
          azureService.getOrCreateActionManagedIdentity(testResource, testAction, testMrgCoordinates, dummyUser, samRequestContext).unsafeRunSync()
        ami should be(testActionManagedIdentity)
        created should be(true)
      }

      "retrieve an existing Action Managed Identity" in {
        val mockCrlService = mock[CrlService]
        val mockDirectoryDAO = mock[DirectoryDAO]
        val mockAzureManagedResourceGroupDAO = mock[AzureManagedResourceGroupDAO]
        val mockPolicyEvaluatorService = mock[PolicyEvaluatorService]

        val azureService = new AzureService(mockCrlService, mockDirectoryDAO, mockAzureManagedResourceGroupDAO, mockPolicyEvaluatorService)

        val testMrgCoordinates = ManagedResourceGroupCoordinates(
          TenantId(UUID.randomUUID().toString),
          SubscriptionId(UUID.randomUUID().toString),
          ManagedResourceGroupName(UUID.randomUUID().toString)
        )
        val testAction = ResourceAction("testAction")
        val testResource = FullyQualifiedResourceId(ResourceTypeName("testResourceType"), ResourceId("testResource"))
        val testActionManagedIdentityId = ActionManagedIdentityId(testResource, testAction, testMrgCoordinates)
        val testDisplayName = azureService.toManagedIdentityNameFromAmiId(testActionManagedIdentityId)
        val testObjectId = ManagedIdentityObjectId(UUID.randomUUID().toString)
        val testActionManagedIdentity = ActionManagedIdentity(testActionManagedIdentityId, testObjectId, testDisplayName)

        when(mockPolicyEvaluatorService.hasPermission(testResource, testAction, dummyUser.id, samRequestContext)).thenReturn(IO.pure(true))
        when(mockDirectoryDAO.loadActionManagedIdentity(testActionManagedIdentityId, samRequestContext)).thenReturn(IO.pure(Option(testActionManagedIdentity)))

        val (ami, created) =
          azureService.getOrCreateActionManagedIdentity(testResource, testAction, testMrgCoordinates, dummyUser, samRequestContext).unsafeRunSync()
        ami should be(testActionManagedIdentity)
        created should be(false)
        verify(mockCrlService, never).buildMsiManager(testMrgCoordinates.tenantId, testMrgCoordinates.subscriptionId)
      }

      "delete an existing Action Managed Identity" in {
        val mockCrlService = mock[CrlService]
        val mockDirectoryDAO = mock[DirectoryDAO]
        val mockAzureManagedResourceGroupDAO = mock[AzureManagedResourceGroupDAO]
        val mockPolicyEvaluatorService = mock[PolicyEvaluatorService]
        val mockMsiManager = mock[MsiManager]
        val mockIdentities = mock[Identities]

        val azureService = new AzureService(mockCrlService, mockDirectoryDAO, mockAzureManagedResourceGroupDAO, mockPolicyEvaluatorService)

        val testMrgCoordinates = ManagedResourceGroupCoordinates(
          TenantId(UUID.randomUUID().toString),
          SubscriptionId(UUID.randomUUID().toString),
          ManagedResourceGroupName(UUID.randomUUID().toString)
        )
        val testAction = ResourceAction("testAction")
        val testResource = FullyQualifiedResourceId(ResourceTypeName("testResourceType"), ResourceId("testResource"))
        val testActionManagedIdentityId = ActionManagedIdentityId(testResource, testAction, testMrgCoordinates)
        val testDisplayName = azureService.toManagedIdentityNameFromAmiId(testActionManagedIdentityId)
        val testObjectId = ManagedIdentityObjectId(UUID.randomUUID().toString)
        val testActionManagedIdentity = ActionManagedIdentity(testActionManagedIdentityId, testObjectId, testDisplayName)

        when(mockDirectoryDAO.loadActionManagedIdentity(testActionManagedIdentityId, samRequestContext)).thenReturn(IO.pure(Option(testActionManagedIdentity)))
        when(mockCrlService.buildMsiManager(testMrgCoordinates.tenantId, testMrgCoordinates.subscriptionId)).thenReturn(IO.pure(mockMsiManager))
        when(mockMsiManager.identities()).thenReturn(mockIdentities)
        doNothing.when(mockIdentities).deleteById(testObjectId.value)
        when(mockDirectoryDAO.deleteActionManagedIdentity(testActionManagedIdentityId, samRequestContext)).thenReturn(IO.pure(()))

        azureService.deleteActionManagedIdentity(testActionManagedIdentityId, samRequestContext).unsafeRunSync()
      }

      "refuse to interact with a user without the action" in {
        val mockCrlService = mock[CrlService]
        val mockDirectoryDAO = mock[DirectoryDAO]
        val mockAzureManagedResourceGroupDAO = mock[AzureManagedResourceGroupDAO]
        val mockPolicyEvaluatorService = mock[PolicyEvaluatorService]

        val azureService = new AzureService(mockCrlService, mockDirectoryDAO, mockAzureManagedResourceGroupDAO, mockPolicyEvaluatorService)

        val testMrgCoordinates = ManagedResourceGroupCoordinates(
          TenantId(UUID.randomUUID().toString),
          SubscriptionId(UUID.randomUUID().toString),
          ManagedResourceGroupName(UUID.randomUUID().toString)
        )
        val testAction = ResourceAction("testAction")
        val testResource = FullyQualifiedResourceId(ResourceTypeName("testResourceType"), ResourceId("testResource"))
        val testActionManagedIdentityId = ActionManagedIdentityId(testResource, testAction, testMrgCoordinates)
        val testDisplayName = azureService.toManagedIdentityNameFromAmiId(testActionManagedIdentityId)
        val testObjectId = ManagedIdentityObjectId(UUID.randomUUID().toString)
        val testActionManagedIdentity = ActionManagedIdentity(testActionManagedIdentityId, testObjectId, testDisplayName)

        when(mockPolicyEvaluatorService.hasPermission(testResource, testAction, dummyUser.id, samRequestContext)).thenReturn(IO.pure(false))

        val thrown = intercept[WorkbenchExceptionWithErrorReport] {
          azureService.getOrCreateActionManagedIdentity(testResource, testAction, testMrgCoordinates, dummyUser, samRequestContext).unsafeRunSync()
        }
        thrown.errorReport.statusCode should be(Some(StatusCodes.Forbidden))
      }
    }
  }
}
