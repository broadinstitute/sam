package org.broadinstitute.dsde.workbench.sam.config

import cats.data.NonEmptyList
import net.ceedubs.ficus.readers.ValueReader
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google.{GcsBucketName, GoogleProject}
import net.ceedubs.ficus.Ficus._
import AppConfig.nonEmptyListReader
import com.typesafe.config.ConfigRenderOptions
import org.broadinstitute.dsde.workbench.google.{KeyId, KeyRingId, Location}

import scala.concurrent.duration.FiniteDuration

/**
  * Created by mbemis on 8/17/17.
  */
final case class GoogleServicesConfig(
                                       appName: String,
                                       appsDomain: String,
                                       appsSubdomain: String,
                                       environment: String,
                                       pemFile: String,
                                       serviceAccountCredentialJson: ServiceAccountCredentialJson,
                                       serviceAccountClientId: String,
                                       serviceAccountClientEmail: WorkbenchEmail,
                                       serviceAccountClientProject: GoogleProject,
                                       subEmail: WorkbenchEmail,
                                       projectServiceAccount: WorkbenchEmail,
                                       groupSyncPubSubConfig: GooglePubSubConfig,
                                       disableUsersPubSubConfig: GooglePubSubConfig,
                                       notificationPubSubProject: String,
                                       notificationTopic: String,
                                       googleKeyCacheConfig: GoogleKeyCacheConfig,
                                       resourceNamePrefix: Option[String],
                                       adminSdkServiceAccounts: Option[NonEmptyList[ServiceAccountConfig]],
                                       googleKms: GoogleKmsConfig,
                                       terraGoogleOrgNumber: String
)

object GoogleServicesConfig {
  implicit val googlePubSubConfigReader: ValueReader[GooglePubSubConfig] = ValueReader.relative { config =>
    GooglePubSubConfig(
      config.getString("pubSubProject"),
      org.broadinstitute.dsde.workbench.util.toScalaDuration(config.getDuration("pollInterval")),
      org.broadinstitute.dsde.workbench.util.toScalaDuration(config.getDuration("pollJitter")),
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

  implicit val serviceAccountConfigReader: ValueReader[ServiceAccountConfig] = ValueReader.relative { config =>
    ServiceAccountConfig(config.root().render(ConfigRenderOptions.concise))
  }

  implicit val googleServicesConfigReader: ValueReader[GoogleServicesConfig] = ValueReader.relative { config =>
    val jsonCredentials = ServiceAccountCredentialJson(
      FirestoreServiceAccountJsonPath(config.getString("pathToFirestoreCredentialJson")),
      DefaultServiceAccountJsonPath(config.getString("pathToDefaultCredentialJson"))
    )

    GoogleServicesConfig(
      config.getString("appName"),
      config.getString("appsDomain"),
      config.getString("appsSubdomain"),
      config.getString("environment"),
      config.getString("pathToPem"),
      jsonCredentials,
      config.getString("serviceAccountClientId"),
      WorkbenchEmail(config.getString("serviceAccountClientEmail")),
      GoogleProject(config.getString("serviceAccountClientProject")),
      WorkbenchEmail(config.getString("subEmail")),
      WorkbenchEmail(config.getString("projectServiceAccount")),
      config.as[GooglePubSubConfig]("groupSync"),
      config.as[GooglePubSubConfig]("disableUsers"),
      config.getString("notifications.project"),
      config.getString("notifications.topicName"),
      config.as[GoogleKeyCacheConfig]("googleKeyCache"),
      config.as[Option[String]]("resourceNamePrefix"),
      config.as[Option[NonEmptyList[ServiceAccountConfig]]]("adminSdkServiceAccounts"),
      config.as[GoogleKmsConfig]("kms"),
      config.getString("terraGoogleOrgNumber")
    )
  }
}

final case class ServiceAccountConfig(json: String) extends AnyVal
final case class FirestoreServiceAccountJsonPath(asString: String) extends AnyVal
final case class DefaultServiceAccountJsonPath(asString: String) extends AnyVal
final case class ServiceAccountCredentialJson(
    firestoreServiceAccountJsonPath: FirestoreServiceAccountJsonPath,
    defaultServiceAccountJsonPath: DefaultServiceAccountJsonPath)
final case class GoogleConfig(googleServicesConfig: GoogleServicesConfig, petServiceAccountConfig: PetServiceAccountConfig)
