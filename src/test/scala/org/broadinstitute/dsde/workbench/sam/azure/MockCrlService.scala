package org.broadinstitute.dsde.workbench.sam.azure

import cats.effect.IO
import com.azure.core.management.Region
import com.azure.core.util.Context
import com.azure.resourcemanager.msi.MsiManager
import com.azure.resourcemanager.msi.models.{Identities, Identity}
import com.azure.resourcemanager.msi.models.Identity.DefinitionStages
import com.azure.resourcemanager.resources.ResourceManager
import com.azure.resourcemanager.resources.fluent.models.ResourceGroupInner
import com.azure.resourcemanager.resources.models.{ResourceGroup, ResourceGroups}
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedResourceId, ResourceId, SamResourceTypes}
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar

import scala.jdk.CollectionConverters._

object MockCrlService extends MockitoSugar {
  val mockMrgName = ManagedResourceGroupName("test-mrg")
  val mockSamSpendProfileResource = FullyQualifiedResourceId(SamResourceTypes.spendProfile, ResourceId("test-spend-profile"))

  def apply() = {
    val mockCrlService = mock[CrlService]
    val mockRm = mockResourceManager
    when(mockCrlService.buildResourceManager(any[TenantId], any[SubscriptionId]))
      .thenReturn(IO.pure(mockRm))

    val mockMsi = mockMsiManager
    when(mockCrlService.buildMsiManager(any[TenantId], any[SubscriptionId]))
      .thenReturn(IO.pure(mockMsi))

    mockCrlService
  }

  private def mockResourceManager: ResourceManager = {
    val mockResourceGroupInner = mock[ResourceGroupInner]
    when(mockResourceGroupInner.managedBy())
      .thenReturn("terra")

    val mockResourceGroup = mock[ResourceGroup]
    when(mockResourceGroup.tags())
      .thenReturn(Map("terra.billingProfileId" -> mockSamSpendProfileResource.resourceId.value).asJava)
    when(mockResourceGroup.innerModel())
      .thenReturn(mockResourceGroupInner)

    val mockResourceGroups = mock[ResourceGroups]
    when(mockResourceGroups.getByName(anyString))
      .thenThrow(new RuntimeException("resource group not found"))
    when(mockResourceGroups.getByName(ArgumentMatchers.eq(mockMrgName.value)))
      .thenReturn(mockResourceGroup)

    val mockResourceManager = mock[ResourceManager]
    when(mockResourceManager.resourceGroups())
      .thenReturn(mockResourceGroups)

    mockResourceManager
  }

  private def mockMsiManager: MsiManager = {
    val mockIdentity = mock[Identity]
    when(mockIdentity.name())
      .thenReturn("mock-uami")
    when(mockIdentity.id())
      .thenReturn("tenant/sub/mrg/uami")

    val createIdentityStage3 = mock[DefinitionStages.WithCreate]
    when(createIdentityStage3.create(any[Context]))
      .thenReturn(mockIdentity)
    when(createIdentityStage3.withTags(any[java.util.Map[String, String]]))
      .thenReturn(createIdentityStage3)

    val createIdentityStage2 = mock[DefinitionStages.WithGroup]
    when(createIdentityStage2.withExistingResourceGroup(anyString))
      .thenReturn(createIdentityStage3)

    val createIdentityStage1 = mock[DefinitionStages.Blank]
    when(createIdentityStage1.withRegion(any[Region]()))
      .thenReturn(createIdentityStage2)

    val mockIdentities = mock[Identities]
    when(mockIdentities.define(anyString))
      .thenReturn(createIdentityStage1)

    val mockMsiManager = mock[MsiManager]
    when(mockMsiManager.identities())
      .thenReturn(mockIdentities)

    mockMsiManager
  }
}
