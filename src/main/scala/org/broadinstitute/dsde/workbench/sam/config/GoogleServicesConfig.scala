package org.broadinstitute.dsde.workbench.sam.config

import cats.data.NonEmptyList
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader
import org.broadinstitute.dsde.workbench.google.{KeyId, KeyRingId, Location}
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GoogleProject}
import org.broadinstitute.dsde.workbench.sam.config.AppConfig.nonEmptyListReader

import scala.concurrent.duration.{Duration, FiniteDuration}

/** Created by mbemis on 8/17/17.
  */
final case class GoogleServicesConfig(
    appName: String,
    appsDomain: String,
    environment: String,
    pemFile: String,
    serviceAccountCredentialJson: ServiceAccountCredentialJson,
    serviceAccountClientId: String,
    serviceAccountClientEmail: WorkbenchEmail,
    serviceAccountClientProject: GoogleProject,
    subEmail: WorkbenchEmail,
    directoryApiAccounts: Option[NonEmptyList[WorkbenchEmail]],
    projectServiceAccount: WorkbenchEmail,
    groupSyncPubSubConfig: GooglePubSubConfig,
    disableUsersPubSubConfig: GooglePubSubConfig,
    notificationPubSubProject: String,
    notificationTopic: String,
    googleKeyCacheConfig: GoogleKeyCacheConfig,
    resourceNamePrefix: Option[String],
    adminSdkServiceAccountPaths: Option[NonEmptyList[String]],
    googleKms: GoogleKmsConfig,
    terraGoogleOrgNumber: String,
    traceExporter: TraceExporterConfig
)

object GoogleServicesConfig {
  implicit val googlePubSubConfigReader: ValueReader[GooglePubSubConfig] = ValueReader.relative { config =>
    GooglePubSubConfig(
      config.getString("pubSubProject"),
      config.getString("pubSubTopic"),
      config.getString("pubSubSubscription"),
      config.getInt("workerCount")
    )
  }
  implicit val googleKeyCacheConfigReader: ValueReader[GoogleKeyCacheConfig] = ValueReader.relative { config =>
    GoogleKeyCacheConfig(
      GcsBucketName(config.getString("bucketName")),
      config.getInt("activeKeyMaxAge"),
      config.getInt("retiredKeyMaxAge"),
      config.as[GooglePubSubConfig]("monitor")
    )
  }

  implicit val googleKmsConfigReader: ValueReader[GoogleKmsConfig] = ValueReader.relative { config =>
    GoogleKmsConfig(
      GoogleProject(config.getString("project")),
      Location(config.getString("location")),
      KeyRingId(config.getString("keyRingId")),
      KeyId(config.getString("keyId")),
      config.as[FiniteDuration]("rotationPeriod")
    )
  }

  implicit val traceExporterConfigReader: ValueReader[TraceExporterConfig] = ValueReader.relative { config =>
    TraceExporterConfig(
      config.getBoolean("enabled"),
      config.getString("projectId"),
      config.getDouble("samplingProbability")
    )
  }

  implicit val googleServicesConfigReader: ValueReader[GoogleServicesConfig] = ValueReader.relative { config =>
    val jsonCredentials = ServiceAccountCredentialJson(
      DefaultServiceAccountJsonPath(config.getString("pathToDefaultCredentialJson"))
    )

    GoogleServicesConfig(
      config.getString("appName"),
      config.getString("appsDomain"),
      config.getString("environment"),
      config.getString("pathToPem"),
      jsonCredentials,
      config.getString("serviceAccountClientId"),
      WorkbenchEmail(config.getString("serviceAccountClientEmail")),
      GoogleProject(config.getString("serviceAccountClientProject")),
      WorkbenchEmail(config.getString("subEmail")),
      config.as[Option[NonEmptyList[String]]]("directoryApiAccounts").map(_.map(WorkbenchEmail)),
      WorkbenchEmail(config.getString("projectServiceAccount")),
      config.as[GooglePubSubConfig]("groupSync"),
      config.as[GooglePubSubConfig]("disableUsers"),
      config.getString("notifications.project"),
      config.getString("notifications.topicName"),
      config.as[GoogleKeyCacheConfig]("googleKeyCache"),
      config.as[Option[String]]("resourceNamePrefix"),
      config.as[Option[NonEmptyList[String]]]("adminSdkServiceAccountPaths"),
      config.as[GoogleKmsConfig]("kms"),
      config.getString("terraGoogleOrgNumber"),
      config.as[TraceExporterConfig]("traceExporter")
    )
  }
}

final case class DefaultServiceAccountJsonPath(asString: String) extends AnyVal
final case class ServiceAccountCredentialJson(defaultServiceAccountJsonPath: DefaultServiceAccountJsonPath)
final case class GoogleConfig(
    googleServicesConfig: GoogleServicesConfig,
    petServiceAccountConfig: PetServiceAccountConfig,
    coordinatedAdminSdkBackoffDuration: Option[Duration]
)
