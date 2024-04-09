package org.broadinstitute.dsde.workbench.sam.config

case class ManagedAppPlan(name: String, publisher: String, authorizedUserKey: String)
case class AzureServicesConfig(
    controlPlaneEnabled: Boolean,
    authorizedUserKey: String,
    kindServiceCatalog: String,
    managedAppWorkloadClientId: String,
    managedAppClientId: String,
    managedAppClientSecret: String,
    managedAppTenantId: String,
    managedAppPlans: Seq[ManagedAppPlan],
    allowManagedIdentityUserCreation: Boolean
) {}
