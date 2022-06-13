package org.broadinstitute.dsde.workbench.sam.azure

import bio.terra.cloudres.azure.resourcemanager.common.Defaults
import bio.terra.cloudres.azure.resourcemanager.msi.data.CreateUserAssignedManagedIdentityRequestData
import bio.terra.cloudres.common.ClientConfig
import cats.effect.IO
import com.azure.core.management.profile.AzureProfile
import com.azure.core.management.{AzureEnvironment, Region}
import com.azure.core.util.Context
import com.azure.identity.{ClientSecretCredential, ClientSecretCredentialBuilder}
import com.azure.resourcemanager.msi.MsiManager
import com.azure.resourcemanager.resources.ResourceManager
import org.broadinstitute.dsde.workbench.sam.config.AzureServicesConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.jdk.CollectionConverters._

class AzureService(azureServicesConfig: AzureServicesConfig,
                   clientConfig: ClientConfig,
                   directoryDAO: DirectoryDAO) {
  // Static region in which to create all managed identities
  private val managedIdentityRegion = Region.US_EAST

  private val billingProfileTag = "terra.billingProfileId"

  def getOrCreateUserPetManagedIdentity(user: SamUser,
                                        request: GetOrCreatePetManagedIdentityRequest,
                                        samRequestContext: SamRequestContext): IO[(PetManagedIdentity, Boolean)] = {
    val id = PetManagedIdentityId(user.id, request.tenantId, request.subscriptionId, request.managedResourceGroupName)
    for {
      existingPetOpt <- directoryDAO.loadPetManagedIdentity(id, samRequestContext)
      pet <- existingPetOpt match {
        // pet exists in Sam DB - return it
        case Some(p) => IO.pure((p, false))
        // pet does not exist in Sam DB - create it
        case None =>
          for {
            manager <- getMsiManager(request.tenantId, request.subscriptionId)
            petName = toManagedIdentityNameFromUser(user)
            context = buildCrlContext(request, petName)
            azureUami <- IO(manager.identities().define(petName.value)
              .withRegion(managedIdentityRegion)
              .withExistingResourceGroup(request.managedResourceGroupName.value)
              .withTags(Map("samUserId" -> user.id.value, "samUserEmail" -> user.email.value).asJava)
              .create(context))
            petToCreate = PetManagedIdentity(id, ManagedIdentityObjectId(azureUami.id()), ManagedIdentityDisplayName(azureUami.name()))
            createdPet <- directoryDAO.createPetManagedIdentity(petToCreate, samRequestContext)
            // TODO write to LDAP
          } yield (createdPet, true)
      }
    } yield pet
  }

  private[azure] def getMsiManager(tenantId: TenantId, subscriptionId: SubscriptionId): IO[MsiManager] = {
    val (credential, profile) = getCredentialAndProfile(tenantId, subscriptionId)
    IO(Defaults.crlConfigure(clientConfig, MsiManager.configure()).authenticate(credential, profile))
  }

  private[azure] def getResourceManager(tenantId: TenantId, subscriptionId: SubscriptionId): IO[ResourceManager] = {
    val (credential, profile) = getCredentialAndProfile(tenantId, subscriptionId)
    IO(Defaults.crlConfigure(clientConfig, ResourceManager.configure()).authenticate(credential, profile).withSubscription(subscriptionId.value))
  }

  private[azure] def getCredentialAndProfile(tenantId: TenantId, subscriptionId: SubscriptionId): (ClientSecretCredential, AzureProfile) = {
    val credential = new ClientSecretCredentialBuilder()
      .clientId(azureServicesConfig.managedAppClientId)
      .clientSecret(azureServicesConfig.managedAppClientSecret)
      .tenantId(azureServicesConfig.managedAppTenantId)
      .build

    val profile = new AzureProfile(
      tenantId.value,
      subscriptionId.value,
      AzureEnvironment.AZURE)

    (credential, profile)
  }

  private[azure] def buildCrlContext(request: GetOrCreatePetManagedIdentityRequest, petName: ManagedIdentityDisplayName): Context = {
    Defaults.buildContext(
      CreateUserAssignedManagedIdentityRequestData.builder()
        .setTenantId(request.tenantId.value)
        .setSubscriptionId(request.subscriptionId.value)
        .setResourceGroupName(request.managedResourceGroupName.value)
        .setName(petName.value)
        .setRegion(managedIdentityRegion)
        .build())
  }

  // Note:
  private[azure] def getBillingProfileId(request: GetOrCreatePetManagedIdentityRequest): IO[Option[ResourceId]] = {
    for {
      resourceManager <- getResourceManager(request.tenantId, request.subscriptionId)
      mrg <- IO(resourceManager.resourceGroups().getByName(request.managedResourceGroupName.value)).attempt
      billingProfileOpt = mrg.toOption.flatMap(_.tags().asScala.get(billingProfileTag))
    } yield billingProfileOpt.map(ResourceId(_))
  }

  private[azure] def toManagedIdentityNameFromUser(user: SamUser): ManagedIdentityDisplayName = {
    ManagedIdentityDisplayName(s"pet-${user.id.value}")
  }

}
