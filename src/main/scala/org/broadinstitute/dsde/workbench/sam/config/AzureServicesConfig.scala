package org.broadinstitute.dsde.workbench.sam.config

import net.ceedubs.ficus.Ficus.{toFicusConfig, traversableReader}
import net.ceedubs.ficus.readers.ValueReader

final case class ManagedAppPlan(name: String, publisher: String, authorizedUserKey: String) {}

final case class AzureServicesConfig(
    azureServiceCatalogAppsEnabled: Boolean,
    authorizedUserKey: String,
    kindServiceCatalog: String,
    managedAppWorkloadClientId: String,
    managedAppClientId: String,
    managedAppClientSecret: String,
    managedAppTenantId: String,
    managedAppPlans: Seq[ManagedAppPlan],
    allowManagedIdentityUserCreation: Boolean
) {}

object AzureServicesConfig {
  implicit val azureConfigReader: ValueReader[AzureServicesConfig] = ValueReader.relative { config =>
    AzureServicesConfig(
      azureServiceCatalogAppsEnabled = config.getBoolean("azureServiceCatalogAppsEnabled"),
      authorizedUserKey = config.getString("authorizedUserKey"),
      kindServiceCatalog = config.getString("kindServiceCatalog"),
      managedAppWorkloadClientId = config.getString("managedAppWorkloadClientId"),
      managedAppClientId = config.getString("managedAppClientId"),
      managedAppClientSecret = config.getString("managedAppClientSecret"),
      managedAppTenantId = config.getString("managedAppTenantId"),
      managedAppPlans = config.as[Seq[ManagedAppPlan]]("managedAppPlans"),
      allowManagedIdentityUserCreation = config.getBoolean("allowManagedIdentityUserCreation")
    )
  }
}

object ManagedAppPlan {
  implicit val managedAppPlanConfigReader: ValueReader[ManagedAppPlan] = ValueReader.relative { config =>
    ManagedAppPlan(
      name = config.getString("name"),
      publisher = config.getString("publisher"),
      authorizedUserKey = config.getString("authorizedUserKey")
    )
  }
}
