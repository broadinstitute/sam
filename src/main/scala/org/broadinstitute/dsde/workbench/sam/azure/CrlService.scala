package org.broadinstitute.dsde.workbench.sam.azure

import bio.terra.cloudres.azure.resourcemanager.common.Defaults
import bio.terra.cloudres.common.ClientConfig
import bio.terra.cloudres.common.cleanup.CleanupConfig
import cats.effect.IO
import com.azure.core.credential.TokenCredential
import com.azure.core.management.AzureEnvironment
import com.azure.core.management.profile.AzureProfile
import com.azure.identity.{ChainedTokenCredentialBuilder, ClientSecretCredentialBuilder, ManagedIdentityCredentialBuilder}
import com.azure.resourcemanager.managedapplications.ApplicationManager
import com.azure.resourcemanager.msi.MsiManager
import com.azure.resourcemanager.resources.ResourceManager
import com.google.auth.oauth2.ServiceAccountCredentials
import org.broadinstitute.dsde.workbench.sam.config.{AzureServicesConfig, JanitorConfig, ManagedAppPlan}

import java.io.FileInputStream
import scala.concurrent.duration._
import scala.jdk.DurationConverters._

/** Service class for interacting with Terra Cloud Resource Library (CRL). See: https://github.com/DataBiosphere/terra-cloud-resource-lib
  *
  * Note: this class is Azure-specific for now because Sam uses workbench-libs for Google Cloud calls.
  */
class CrlService(config: AzureServicesConfig, janitorConfig: JanitorConfig) {
  val clientId = "sam"
  val testResourceTimeToLive = 1 hour
  val clientConfigBase = ClientConfig.Builder.newBuilder().setClient("sam")
  val clientConfig = if (janitorConfig.enabled) {
    clientConfigBase
      .setCleanupConfig(
        CleanupConfig
          .builder()
          .setCleanupId(s"$clientId-test")
          .setTimeToLive(testResourceTimeToLive.toJava)
          .setJanitorProjectId(janitorConfig.trackResourceProjectId.value)
          .setJanitorTopicName(janitorConfig.trackResourceTopicName)
          .setCredentials(ServiceAccountCredentials.fromStream(new FileInputStream(janitorConfig.clientCredential.defaultServiceAccountJsonPath.asString)))
          .build()
      )
      .build()
  } else {
    clientConfigBase.build()
  }

  def buildMsiManager(tenantId: TenantId, subscriptionId: SubscriptionId): IO[MsiManager] = {
    val (credential, profile) = getCredentialAndProfile(tenantId, subscriptionId)
    IO(Defaults.crlConfigure(clientConfig, MsiManager.configure()).authenticate(credential, profile))
  }

  def buildResourceManager(tenantId: TenantId, subscriptionId: SubscriptionId): IO[ResourceManager] = {
    val (credential, profile) = getCredentialAndProfile(tenantId, subscriptionId)
    IO(Defaults.crlConfigure(clientConfig, ResourceManager.configure()).authenticate(credential, profile).withSubscription(subscriptionId.value))
  }

  def buildApplicationManager(tenantId: TenantId, subscriptionId: SubscriptionId): IO[ApplicationManager] = {
    val (credential, profile) = getCredentialAndProfile(tenantId, subscriptionId)
    IO(ApplicationManager.authenticate(credential, profile))
  }

  def getManagedAppPlans: Seq[ManagedAppPlan] = config.managedAppPlans
  def getAzureControlPlaneEnabled: Boolean = config.azureControlPlaneEnabled
  def getAuthorizedUserKey: String = config.authorizedUserKey
  def getKindServiceCatalog: String = config.kindServiceCatalog

  private def getCredentialAndProfile(tenantId: TenantId, subscriptionId: SubscriptionId): (TokenCredential, AzureProfile) = {

    val managedIdentityCredential = new ManagedIdentityCredentialBuilder()
      .clientId(config.managedAppWorkloadClientId)
      .build

    val servicePrincipalCredential = new ClientSecretCredentialBuilder()
      .clientId(config.managedAppClientId)
      .clientSecret(config.managedAppClientSecret)
      .tenantId(config.managedAppTenantId)
      .build

    // When an access token is requested, the chain will try each
    // credential in order, stopping when one provides a token
    //
    // For Managed Identity auth, SAM must be deployed to an Azure service
    // other platforms will fall through to Service Principal auth
    val credential = new ChainedTokenCredentialBuilder()
      .addLast(managedIdentityCredential)
      .addLast(servicePrincipalCredential)
      .build

    val profile = new AzureProfile(tenantId.value, subscriptionId.value, AzureEnvironment.AZURE)

    (credential, profile)
  }

}
