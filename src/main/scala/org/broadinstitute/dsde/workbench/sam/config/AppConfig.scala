package org.broadinstitute.dsde.workbench.sam.config

import cats.data.NonEmptyList
import com.google.api.client.json.gson.GsonFactory
import com.typesafe.config._
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.sam.config.AppConfig.AdminConfig
import org.broadinstitute.dsde.workbench.sam.config.GoogleServicesConfig.googleServicesConfigReader
import org.broadinstitute.dsde.workbench.sam.dataAccess.DistributedLockConfig
import org.broadinstitute.dsde.workbench.sam.model._

import scala.concurrent.duration.Duration

/** Created by dvoet on 7/18/17.
  */
final case class AppConfig(
    emailDomain: String,
    distributedLockConfig: DistributedLockConfig,
    googleConfig: Option[GoogleConfig],
    resourceTypes: Set[ResourceType],
    liquibaseConfig: LiquibaseConfig,
    blockedEmailDomains: Seq[String],
    termsOfServiceConfig: TermsOfServiceConfig,
    oidcConfig: OidcConfig,
    adminConfig: AdminConfig,
    azureServicesConfig: Option[AzureServicesConfig]
)

object AppConfig {
  implicit val oidcReader: ValueReader[OidcConfig] = ValueReader.relative { config =>
    OidcConfig(
      config.getString("authorityEndpoint"),
      config.getString("oidcClientId"),
      config.as[Option[String]]("oidcClientSecret"),
      config.as[Option[String]]("legacyGoogleClientId")
    )
  }

  def unquoteAndEscape(str: String): String = str.replace("\"", "").replaceAll("[:.+]+", "\"$0\"")

  implicit object resourceRoleReader extends ValueReader[ResourceRole] {
    override def read(config: Config, path: String): ResourceRole = {
      val uqPath = unquoteAndEscape(path)
      ResourceRole(
        ResourceRoleName(uqPath),
        config.as[Set[String]](s"$uqPath.roleActions").map(ResourceAction.apply),
        config.as[Option[Set[String]]](s"$uqPath.includedRoles").getOrElse(Set.empty).map(ResourceRoleName.apply),
        config
          .as[Option[Map[String, Set[String]]]](s"$uqPath.descendantRoles")
          .getOrElse(Map.empty)
          .map { case (resourceTypeName, roleNames) =>
            (ResourceTypeName(resourceTypeName), roleNames.map(ResourceRoleName.apply))
          }
      )
    }
  }

  implicit object resourceActionPatternReader extends ValueReader[ResourceActionPattern] {
    override def read(config: Config, path: String): ResourceActionPattern = {
      val uqPath = unquoteAndEscape(path)
      ResourceActionPattern(
        uqPath,
        config.getString(s"$uqPath.description"),
        config.as[Option[Boolean]](s"$uqPath.authDomainConstrainable").getOrElse(false)
      )
    }
  }

  implicit object resourceTypeReader extends ValueReader[ResourceType] {
    override def read(config: Config, path: String): ResourceType = {
      val uqPath = unquoteAndEscape(path)

      ResourceType(
        ResourceTypeName(uqPath),
        config.as[Map[String, ResourceActionPattern]](s"$uqPath.actionPatterns").values.toSet,
        config.as[Map[String, ResourceRole]](s"$uqPath.roles").values.toSet,
        ResourceRoleName(config.getString(s"$uqPath.ownerRoleName")),
        config.getBoolean(s"$uqPath.reuseIds"),
        config.as[Option[Boolean]](s"$uqPath.allowLeaving").getOrElse(false)
      )
    }
  }

  val jsonFactory = GsonFactory.getDefaultInstance

  implicit def nonEmptyListReader[A](implicit valueReader: ValueReader[List[A]]): ValueReader[Option[NonEmptyList[A]]] =
    new ValueReader[Option[NonEmptyList[A]]] {
      def read(config: Config, path: String): Option[NonEmptyList[A]] =
        if (config.hasPath(path)) {
          NonEmptyList.fromList(valueReader.read(config, path))
        } else {
          None
        }
    }

  implicit val petServiceAccountConfigReader: ValueReader[PetServiceAccountConfig] = ValueReader.relative { config =>
    PetServiceAccountConfig(
      GoogleProject(config.getString("googleProject")),
      config.as[Set[String]]("serviceAccountUsers").map(WorkbenchEmail)
    )
  }

  implicit val distributedLockConfigReader: ValueReader[DistributedLockConfig] = ValueReader.relative { config =>
    val retryInterval = config.getDuration("retryInterval")

    DistributedLockConfig(
      Duration.fromNanos(retryInterval.toNanos),
      config.getInt("maxRetry")
    )
  }

  implicit val termsOfServiceConfigReader: ValueReader[TermsOfServiceConfig] = ValueReader.relative { config =>
    TermsOfServiceConfig(
      config.getBoolean("enabled"),
      config.getBoolean("isGracePeriodEnabled"),
      config.getString("version"),
      config.getString("url")
    )
  }

  implicit val liquibaseConfigReader: ValueReader[LiquibaseConfig] = ValueReader.relative { config =>
    LiquibaseConfig(config.getString("changelog"), config.getBoolean("initWithLiquibase"))
  }

  final case class AdminConfig(superAdminsGroup: WorkbenchEmail, allowedEmailDomains: Set[String])

  implicit val adminConfigReader: ValueReader[AdminConfig] = ValueReader.relative { config =>
    AdminConfig(
      superAdminsGroup = WorkbenchEmail(config.getString("superAdminsGroup")),
      allowedEmailDomains = config.as[Set[String]]("allowedAdminEmailDomains")
    )
  }

  implicit val azureServicesConfigReader: ValueReader[AzureServicesConfig] = ValueReader.relative { config =>
    AzureServicesConfig(
      config.getString("managedAppClientId"),
      config.getString("managedAppClientSecret"),
      config.getString("managedAppTenantId"),
      config.as[Seq[String]]("managedAppPlanIds")
    )
  }

  def readConfig(config: Config): AppConfig = {
    val googleConfigOption = for {
      googleServices <- config.getAs[GoogleServicesConfig]("googleServices")
    } yield GoogleConfig(googleServices, config.as[PetServiceAccountConfig]("petServiceAccount"))

    // TODO - https://broadinstitute.atlassian.net/browse/GAWB-3603
    // This should JUST get the value from "emailDomain", but for now we're keeping the backwards compatibility code to
    // fall back to getting the "googleServices.appsDomain"
    val emailDomain = config.as[Option[String]]("emailDomain").getOrElse(config.getString("googleServices.appsDomain"))

    AppConfig(
      emailDomain,
      distributedLockConfig = config.as[DistributedLockConfig]("distributedLock"),
      googleConfigOption,
      resourceTypes = config.as[Map[String, ResourceType]]("resourceTypes").values.toSet,
      liquibaseConfig = config.as[LiquibaseConfig]("liquibase"),
      blockedEmailDomains = config.as[Option[Seq[String]]]("blockedEmailDomains").getOrElse(Seq.empty),
      termsOfServiceConfig = config.as[TermsOfServiceConfig]("termsOfService"),
      oidcConfig = config.as[OidcConfig]("oidc"),
      adminConfig = config.as[AdminConfig]("admin"),
      azureServicesConfig = config.getAs[AzureServicesConfig]("azureServices")
    )
  }
}
