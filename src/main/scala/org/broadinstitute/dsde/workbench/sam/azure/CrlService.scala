package org.broadinstitute.dsde.workbench.sam.azure

import bio.terra.cloudres.azure.resourcemanager.common.Defaults
import bio.terra.cloudres.common.ClientConfig
import cats.effect.IO
import com.azure.core.management.AzureEnvironment
import com.azure.core.management.profile.AzureProfile
import com.azure.identity.{ClientSecretCredential, ClientSecretCredentialBuilder}
import com.azure.resourcemanager.msi.MsiManager
import com.azure.resourcemanager.resources.ResourceManager
import org.broadinstitute.dsde.workbench.sam.config.AzureServicesConfig

/**
  * Service class for interacting with Terra Cloud Resource Library (CRL).
  * See: https://github.com/DataBiosphere/terra-cloud-resource-lib
  *
  * Note: this class is Azure-specific for now because Sam uses workbench-libs for
  * Google Cloud calls.
  */
class CrlService(config: AzureServicesConfig) {
  // TODO: Update Sam tests to talk to Janitor service:
  // https://broadworkbench.atlassian.net/browse/ID-96
  val clientConfig = ClientConfig.Builder.newBuilder().setClient("sam").build()

  def buildMsiManager(tenantId: TenantId, subscriptionId: SubscriptionId): IO[MsiManager] = {
    val (credential, profile) = getCredentialAndProfile(tenantId, subscriptionId)
    IO(Defaults.crlConfigure(clientConfig, MsiManager.configure()).authenticate(credential, profile))
  }

  def buildResourceManager(tenantId: TenantId, subscriptionId: SubscriptionId): IO[ResourceManager] = {
    val (credential, profile) = getCredentialAndProfile(tenantId, subscriptionId)
    IO(Defaults.crlConfigure(clientConfig, ResourceManager.configure()).authenticate(credential, profile).withSubscription(subscriptionId.value))
  }

  private def getCredentialAndProfile(tenantId: TenantId, subscriptionId: SubscriptionId): (ClientSecretCredential, AzureProfile) = {
    val credential = new ClientSecretCredentialBuilder()
      .clientId(config.managedAppClientId)
      .clientSecret(config.managedAppClientSecret)
      .tenantId(config.managedAppTenantId)
      .build

    val profile = new AzureProfile(
      tenantId.value,
      subscriptionId.value,
      AzureEnvironment.AZURE)

    (credential, profile)
  }
}
