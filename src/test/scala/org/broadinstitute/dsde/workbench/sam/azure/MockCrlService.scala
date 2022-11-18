package org.broadinstitute.dsde.workbench.sam.azure

import cats.effect.IO
import com.azure.core.http.rest.PagedIterable
import com.azure.core.management.Region
import com.azure.core.util.Context
import com.azure.resourcemanager.managedapplications.ApplicationManager
import com.azure.resourcemanager.managedapplications.models.{Application, Applications, Plan}
import com.azure.resourcemanager.msi.MsiManager
import com.azure.resourcemanager.msi.models.{Identities, Identity}
import com.azure.resourcemanager.msi.models.Identity.DefinitionStages
import com.azure.resourcemanager.resources.ResourceManager
import com.azure.resourcemanager.resources.fluent.models.ResourceGroupInner
import com.azure.resourcemanager.resources.models.{ResourceGroup, ResourceGroups}
import org.broadinstitute.dsde.workbench.sam.config.ManagedAppPlan
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedResourceId, ResourceId, SamResourceTypes, SamUser}
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{RETURNS_SMART_NULLS, when}
import org.scalatestplus.mockito.MockitoSugar

import scala.jdk.CollectionConverters._

object MockCrlService extends MockitoSugar {
  val mockMrgName = ManagedResourceGroupName("test-mrg")
  val mockSamSpendProfileResource = FullyQualifiedResourceId(SamResourceTypes.spendProfile, ResourceId("test-spend-profile"))
  val mockPlan = ManagedAppPlan("mock-plan", "mock-publisher", "mock-auth-user-key")

  def apply(user: Option[SamUser] = None) = {
    val mockCrlService = mock[CrlService](RETURNS_SMART_NULLS)
    val mockRm = mockResourceManager
    val mockAppMgr = mockApplicationManager(user)

    when(mockCrlService.buildResourceManager(any[TenantId], any[SubscriptionId]))
      .thenReturn(IO.pure(mockRm))

    val mockMsi = mockMsiManager
    when(mockCrlService.buildMsiManager(any[TenantId], any[SubscriptionId]))
      .thenReturn(IO.pure(mockMsi))

    when(mockCrlService.getManagedAppPlans)
      .thenReturn(Seq(mockPlan))

    when(mockCrlService.buildApplicationManager(any[TenantId], any[SubscriptionId]))
      .thenReturn(IO.pure(mockAppMgr))

    mockCrlService
  }

  private def mockResourceManager: ResourceManager = {
    // Mock get resource group
    val mockResourceGroupInner = mock[ResourceGroupInner](RETURNS_SMART_NULLS)
    when(mockResourceGroupInner.managedBy())
      .thenReturn("terra")

    val mockResourceGroup = mock[ResourceGroup](RETURNS_SMART_NULLS)
    when(mockResourceGroup.tags())
      .thenReturn(Map("terra.billingProfileId" -> mockSamSpendProfileResource.resourceId.value).asJava)
    when(mockResourceGroup.innerModel())
      .thenReturn(mockResourceGroupInner)
    when(mockResourceGroup.id())
      .thenReturn(mockMrgName.value)

    val mockResourceGroups = mock[ResourceGroups](RETURNS_SMART_NULLS)
    when(mockResourceGroups.getByName(anyString))
      .thenThrow(new RuntimeException("resource group not found"))
    when(mockResourceGroups.getByName(ArgumentMatchers.eq(mockMrgName.value)))
      .thenReturn(mockResourceGroup)

    // Mock resource manager
    val mockResourceManager = mock[ResourceManager](RETURNS_SMART_NULLS)
    when(mockResourceManager.resourceGroups())
      .thenReturn(mockResourceGroups)

    mockResourceManager
  }

  private def mockMsiManager: MsiManager = {
    // Mock create identity
    val mockIdentity = mock[Identity](RETURNS_SMART_NULLS)
    when(mockIdentity.name())
      .thenReturn("mock-uami")
    when(mockIdentity.id())
      .thenReturn("tenant/sub/mrg/uami")

    val createIdentityStage3 = mock[DefinitionStages.WithCreate](RETURNS_SMART_NULLS)
    when(createIdentityStage3.create(any[Context]))
      .thenReturn(mockIdentity)
    when(createIdentityStage3.withTags(any[java.util.Map[String, String]]))
      .thenReturn(createIdentityStage3)

    val createIdentityStage2 = mock[DefinitionStages.WithGroup](RETURNS_SMART_NULLS)
    when(createIdentityStage2.withExistingResourceGroup(anyString))
      .thenReturn(createIdentityStage3)

    val createIdentityStage1 = mock[DefinitionStages.Blank](RETURNS_SMART_NULLS)
    when(createIdentityStage1.withRegion(any[Region]()))
      .thenReturn(createIdentityStage2)

    val mockIdentities = mock[Identities](RETURNS_SMART_NULLS)
    when(mockIdentities.define(anyString))
      .thenReturn(createIdentityStage1)

    // Mock MsiManager
    val mockMsiManager = mock[MsiManager](RETURNS_SMART_NULLS)
    when(mockMsiManager.identities())
      .thenReturn(mockIdentities)

    mockMsiManager
  }

  private def mockApplicationManager(user: Option[SamUser]): ApplicationManager = {
    val appParameters = user.map(u => Map[String, Map[String, String]](mockPlan.authorizedUserKey -> Map("value" -> u.email.value)).view.mapValues(_.asJava).toMap.asJava)
    val mockApplication = mock[Application](RETURNS_SMART_NULLS)
    when(mockApplication.managedResourceGroupId())
      .thenReturn(mockMrgName.value)
    when(mockApplication.plan())
      .thenReturn(new Plan().withName(mockPlan.name).withPublisher(mockPlan.publisher))
    when(mockApplication.parameters())
      .thenReturn(appParameters.orNull)

    val mockApplicationIterator = mock[PagedIterable[Application]](RETURNS_SMART_NULLS)
    when(mockApplicationIterator.iterator())
      .thenReturn(List(mockApplication).iterator.asJava)

    val mockApplications = mock[Applications](RETURNS_SMART_NULLS)
    when(mockApplications.list())
      .thenReturn(mockApplicationIterator)

    val mockAppMgr = mock[ApplicationManager](RETURNS_SMART_NULLS)
    when(mockAppMgr.applications())
      .thenReturn(mockApplications)

    mockAppMgr
  }
}
