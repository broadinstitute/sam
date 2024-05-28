package org.broadinstitute.dsde.workbench.sam.config

final case class ManagedAppPlan(name: String, publisher: String, authorizedUserKey: String) {}

final case class AzureServicesConfig(
    azureServiceCatalogAppsEnabled: Boolean,
    authorizedUserKey: String,
    managedAppTypeServiceCatalog: String,
    managedAppWorkloadClientId: String,
    managedAppClientId: String,
    managedAppClientSecret: String,
    managedAppTenantId: String,
    managedAppPlans: Seq[ManagedAppPlan],
    allowManagedIdentityUserCreation: Boolean
) {}
