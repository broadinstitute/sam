package org.broadinstitute.dsde.workbench.sam.azure

import cats.effect.IO
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
import org.broadinstitute.dsde.workbench.sam.config.ManagedAppPlan
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedResourceId, ResourceId, SamResourceTypes, SamUser}
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.{RETURNS_SMART_NULLS, lenient}
import org.mockito.scalatest.MockitoSugar

import scala.jdk.CollectionConverters._

object MockCrlService extends MockitoSugar {
  val mockMrgName = ManagedResourceGroupName("test-mrg")
  val mockSamSpendProfileResource = FullyQualifiedResourceId(SamResourceTypes.spendProfile, ResourceId("test-spend-profile"))
  val defaultManagedAppPlan = ManagedAppPlan("mock-plan", "mock-publisher", "mock-auth-user-key")

  def apply(
      user: Option[SamUser] = None,
      mrgName: ManagedResourceGroupName = mockMrgName,
      managedAppPlan: ManagedAppPlan = defaultManagedAppPlan,
      includeBillingProfileTag: Boolean = false
  ) = {
    val mockCrlService = mock[CrlService](RETURNS_SMART_NULLS)
    val mockRm = mockResourceManager(mrgName, includeBillingProfileTag)
    val mockAppMgr = mockApplicationManager(user, mrgName, managedAppPlan)

    lenient()
      .when(mockCrlService.buildResourceManager(any[TenantId], any[SubscriptionId]))
      .thenReturn(IO.pure(mockRm))

    val mockMsi = mockMsiManager
    lenient()
      .when(mockCrlService.buildMsiManager(any[TenantId], any[SubscriptionId]))
      .thenReturn(IO.pure(mockMsi))

    lenient()
      .when(mockCrlService.getManagedAppPlans)
      .thenReturn(Seq(managedAppPlan))

    lenient()
      .when(mockCrlService.buildApplicationManager(any[TenantId], any[SubscriptionId]))
      .thenReturn(IO.pure(mockAppMgr))

    mockCrlService
  }

  private def mockResourceManager(mrgName: ManagedResourceGroupName, includeBillingProfileTag: Boolean): ResourceManager = {
    // Mock get resource group
    val mockResourceGroup = mock[ResourceGroup](RETURNS_SMART_NULLS)
    if (includeBillingProfileTag) {
      lenient()
        .when(mockResourceGroup.tags())
        .thenReturn(Map("terra.billingProfileId" -> mockSamSpendProfileResource.resourceId.value).asJava)
    }
    lenient()
      .when(mockResourceGroup.id())
      .thenReturn(mrgName.value)

    val mockResourceGroups = mock[ResourceGroups](RETURNS_SMART_NULLS)
    lenient()
      .when(mockResourceGroups.getByName(anyString))
      .thenThrow(new RuntimeException("resource group not found"))
    lenient()
      .when(mockResourceGroups.getByName(ArgumentMatchers.eq(mrgName.value)))
      .thenReturn(mockResourceGroup)

    // Mock resource manager
    val mockResourceManager = mock[ResourceManager](RETURNS_SMART_NULLS)
    lenient()
      .when(mockResourceManager.resourceGroups())
      .thenReturn(mockResourceGroups)

    mockResourceManager
  }

  private def mockMsiManager: MsiManager = {
    // Mock create identity
    val mockIdentity = mock[Identity](RETURNS_SMART_NULLS)
    lenient()
      .when(mockIdentity.name())
      .thenReturn("mock-uami")
    lenient()
      .when(mockIdentity.id())
      .thenReturn("tenant/sub/mrg/uami")

    val createIdentityStage3 = mock[DefinitionStages.WithCreate](RETURNS_SMART_NULLS)
    lenient()
      .when(createIdentityStage3.create(any[Context]))
      .thenReturn(mockIdentity)
    lenient()
      .when(createIdentityStage3.withTags(any[java.util.Map[String, String]]))
      .thenReturn(createIdentityStage3)

    val createIdentityStage2 = mock[DefinitionStages.WithGroup](RETURNS_SMART_NULLS)
    lenient()
      .when(createIdentityStage2.withExistingResourceGroup(anyString))
      .thenReturn(createIdentityStage3)

    val createIdentityStage1 = mock[DefinitionStages.Blank](RETURNS_SMART_NULLS)
    lenient()
      .when(createIdentityStage1.withRegion(any[Region]))
      .thenReturn(createIdentityStage2)

    val mockIdentities = mock[Identities](RETURNS_SMART_NULLS)
    lenient()
      .when(mockIdentities.define(anyString))
      .thenReturn(createIdentityStage1)

    // Mock MsiManager
    val mockMsiManager = mock[MsiManager](RETURNS_SMART_NULLS)
    lenient()
      .when(mockMsiManager.identities())
      .thenReturn(mockIdentities)

    mockMsiManager
  }

  private def mockApplicationManager(user: Option[SamUser], mrgName: ManagedResourceGroupName, managedAppPlan: ManagedAppPlan): ApplicationManager = {
    val appParameters =
      user.map(u => Map[String, Map[String, String]](managedAppPlan.authorizedUserKey -> Map("value" -> u.email.value)).view.mapValues(_.asJava).toMap.asJava)
    val mockApplication = mock[Application](RETURNS_SMART_NULLS)
    lenient()
      .when(mockApplication.managedResourceGroupId())
      .thenReturn(mrgName.value)
    lenient()
      .when(mockApplication.plan())
      .thenReturn(new Plan().withName(managedAppPlan.name).withPublisher(managedAppPlan.publisher))
    lenient()
      .when(mockApplication.parameters())
      .thenReturn(appParameters.orNull)

    val mockApplicationIterator = mock[PagedIterable[Application]](RETURNS_SMART_NULLS)
    // use thenAnswer instead of thenReturn so we get a new iterator ever time
    // otherwise you get something like an up-only elevator, you only get 1 ride
    lenient()
      .when(mockApplicationIterator.iterator())
      .thenAnswer(_ => List(mockApplication).iterator.asJava)

    val mockApplications = mock[Applications](RETURNS_SMART_NULLS)
    lenient()
      .when(mockApplications.list())
      .thenReturn(mockApplicationIterator)

    val mockAppMgr = mock[ApplicationManager](RETURNS_SMART_NULLS)
    lenient()
      .when(mockAppMgr.applications())
      .thenReturn(mockApplications)

    mockAppMgr
  }
}
