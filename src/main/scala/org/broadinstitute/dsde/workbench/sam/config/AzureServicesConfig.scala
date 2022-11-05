package org.broadinstitute.dsde.workbench.sam.config

case class AzureServicesConfig(
    azureEnabled: Option[Boolean],
    managedAppClientId: String,
    managedAppClientSecret: String,
    managedAppTenantId: String,
    managedAppPlanIds: Seq[String]
) {}
