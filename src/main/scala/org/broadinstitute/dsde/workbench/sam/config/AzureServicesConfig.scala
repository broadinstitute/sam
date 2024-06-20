package org.broadinstitute.dsde.workbench.sam.config

case class ManagedAppPlan(name: String, publisher: String, authorizedUserKey: String)
case class AzureMarketPlace(managedAppPlans: Seq[ManagedAppPlan])
case class AzureServiceCatalog(authorizedUserKey: String, managedAppTypeServiceCatalog: String)
case class AzureServicePrincipalConfig(
    clientId: String,
    clientSecret: String,
    tenantId: String
)
case class AzureServicesConfig(
    managedAppWorkloadClientId: Option[String],
    managedAppServicePrincipal: Option[AzureServicePrincipalConfig],
    azureMarketPlace: Option[AzureMarketPlace],
    azureServiceCatalog: Option[AzureServiceCatalog],
    allowManagedIdentityUserCreation: Boolean
) {}
