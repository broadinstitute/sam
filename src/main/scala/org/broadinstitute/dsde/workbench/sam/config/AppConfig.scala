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
import org.broadinstitute.dsde.workbench.sam.model.api.AccessPolicyMembershipRequest

import java.time.Instant
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters._

/** Created by dvoet on 7/18/17.
  */
final case class AppConfig(
    emailDomain: String,
    distributedLockConfig: DistributedLockConfig,
    googleConfig: Option[GoogleConfig],
    resourceTypes: Set[ResourceType],
    liquibaseConfig: LiquibaseConfig,
    samDatabaseConfig: SamDatabaseConfig,
    blockedEmailDomains: Seq[String],
    termsOfServiceConfig: TermsOfServiceConfig,
    oidcConfig: OidcConfig,
    adminConfig: AdminConfig,
    azureServicesConfig: Option[AzureServicesConfig],
    prometheusConfig: PrometheusConfig,
    janitorConfig: JanitorConfig,
    resourceAccessPolicies: Map[FullyQualifiedPolicyId, AccessPolicyMembershipRequest]
)

object AppConfig {
  implicit val oidcReader: ValueReader[OidcConfig] = ValueReader.relative { config =>
    OidcConfig(
      config.getString("authorityEndpoint"),
      config.getString("oidcClientId")
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
      config.getAs[Boolean]("isTosEnabled").getOrElse(true),
      config.getBoolean("isGracePeriodEnabled"),
      config.getString("version"),
      config.getString("baseUrl"),
      // Must be a valid UTC datetime string in ISO 8601 format ex: 2007-12-03T10:15:30.00Z
      config.as[Option[String]]("rollingAcceptanceWindowExpirationDatetime").map(Instant.parse),
      config.as[Option[String]]("previousVersion"),
      config.as[Option[String]]("acceptanceUrl")
    )
  }

  implicit val liquibaseConfigReader: ValueReader[LiquibaseConfig] = ValueReader.relative { config =>
    LiquibaseConfig(config.getString("changelog"), config.getBoolean("initWithLiquibase"))
  }

  implicit val databaseConfigReader: ValueReader[DatabaseConfig] = ValueReader.relative { config =>
    DatabaseConfig(
      Symbol(config.getString("poolName")),
      config.getInt("poolInitialSize"),
      config.getInt("poolMaxSize"),
      config.getInt("poolConnectionTimeoutMillis"),
      config.getString("driver"),
      config.getString("url"),
      config.getString("user"),
      config.getString("password")
    )
  }

  implicit val samDatabaseConfigReader: ValueReader[SamDatabaseConfig] = ValueReader.relative { config =>
    SamDatabaseConfig(
      config.as[DatabaseConfig]("sam_read"),
      config.as[DatabaseConfig]("sam_write"),
      config.as[DatabaseConfig]("sam_background"),
      config.as[DatabaseConfig]("sam_read_replica")
    )
  }

  final case class AdminConfig(superAdminsGroup: WorkbenchEmail, allowedEmailDomains: Set[String], serviceAccountAdmins: Set[WorkbenchEmail])

  implicit val adminConfigReader: ValueReader[AdminConfig] = ValueReader.relative { config =>
    AdminConfig(
      superAdminsGroup = WorkbenchEmail(config.getString("superAdminsGroup")),
      allowedEmailDomains = config.as[Set[String]]("allowedAdminEmailDomains"),
      serviceAccountAdmins = config.getString("serviceAccountAdmins").split(",").map(e => WorkbenchEmail(e.trim)).toSet
    )
  }

  implicit val azureManagedAppPlanReader: ValueReader[ManagedAppPlan] = ValueReader.relative { config =>
    ManagedAppPlan(config.getString("name"), config.getString("publisher"), config.getString("authorizedUserKey"))
  }

  implicit val azureMarketPlaceReader: ValueReader[Option[AzureMarketPlace]] = ValueReader.relative { config =>
    // enabled by default
    if (config.as[Option[Boolean]]("enabled").getOrElse(true)) {
      Option(AzureMarketPlace(config.as[Seq[ManagedAppPlan]]("managedAppPlans")))
    } else {
      None
    }
  }

  implicit val azureServiceCatalogReader: ValueReader[Option[AzureServiceCatalog]] = ValueReader.relative { config =>
    // disabled by default
    if (config.as[Option[Boolean]]("enabled").getOrElse(false)) {
      Option(AzureServiceCatalog(config.getString("authorizedUserKey"), config.getString("managedAppTypeServiceCatalog")))
    } else {
      None
    }
  }

  implicit val azureServicePrincipalConfigReader: ValueReader[Option[AzureServicePrincipalConfig]] = ValueReader.relative { config =>
    for {
      clientId <- config.getAs[String]("clientId")
      clientSecret <- config.getAs[String]("clientSecret")
      tenantId <- config.getAs[String]("tenantId")
    } yield AzureServicePrincipalConfig(clientId, clientSecret, tenantId)
  }

  implicit val azureServicesConfigReader: ValueReader[Option[AzureServicesConfig]] = ValueReader.relative { config =>
    config
      .getAs[Boolean]("azureEnabled")
      .flatMap(azureEnabled =>
        if (azureEnabled) {
          Option(
            AzureServicesConfig(
              config.as[Option[String]]("managedAppWorkloadClientId"),
              config.as[Option[AzureServicePrincipalConfig]]("managedAppServicePrincipal"),
              config.as[Option[AzureMarketPlace]]("azureMarketPlace"),
              config.as[Option[AzureServiceCatalog]]("azureServiceCatalog"),
              config.as[Option[Boolean]]("allowManagedIdentityUserCreation").getOrElse(false)
            )
          )
        } else {
          None
        }
      )
  }

  implicit val prometheusConfig: ValueReader[PrometheusConfig] = ValueReader.relative { config =>
    PrometheusConfig(config.getInt("endpointPort"))
  }

  implicit val janitorConfig: ValueReader[JanitorConfig] = ValueReader.relative { config =>
    JanitorConfig(
      config.getBoolean("enabled"),
      ServiceAccountCredentialJson(
        DefaultServiceAccountJsonPath(config.getString("clientCredentialFilePath"))
      ),
      GoogleProject(config.getString("trackResourceProjectId")),
      config.getString("trackResourceTopicId")
    )
  }

  implicit val accessPolicyDescendantPermissionsReader: ValueReader[AccessPolicyDescendantPermissions] = ValueReader.relative { config =>
    AccessPolicyDescendantPermissions(
      ResourceTypeName(config.as[String]("resourceTypeName")),
      config.as[Option[Set[String]]]("actions").getOrElse(Set.empty).map(ResourceAction.apply),
      config.as[Option[Set[String]]]("roles").getOrElse(Set.empty).map(ResourceRoleName.apply)
    )
  }

  implicit val policyIdentifiersReader: ValueReader[PolicyIdentifiers] = ValueReader.relative { config =>
    PolicyIdentifiers(
      AccessPolicyName(config.as[String]("accessPolicyName")),
      ResourceTypeName(config.as[String]("resourceTypeName")),
      ResourceId(config.as[String]("resourceId"))
    )
  }

  implicit val accessPolicyMembershipRequestReader: ValueReader[AccessPolicyMembershipRequest] = ValueReader.relative { config =>
    AccessPolicyMembershipRequest(
      config.as[Set[String]]("memberEmails").map(WorkbenchEmail),
      config.as[Option[Set[String]]]("actions").getOrElse(Set.empty).map(ResourceAction.apply),
      config.as[Option[Set[String]]]("roles").getOrElse(Set.empty).map(ResourceRoleName.apply),
      config.as[Option[Set[AccessPolicyDescendantPermissions]]]("descendantPermissions"),
      config.as[Option[Set[PolicyIdentifiers]]]("memberPolicies")
    )
  }

  implicit val resourceAccessPoliciesConfigReader: ValueReader[Map[FullyQualifiedPolicyId, AccessPolicyMembershipRequest]] = ValueReader.relative { config =>
    val policies = for {
      resourceTypeName <- config.root().keySet().asScala
      resourceId <- config.getConfig(resourceTypeName).root().keySet().asScala
      policyName <- config.getConfig(s"$resourceTypeName.$resourceId").root().keySet().asScala
    } yield {
      val fullyQualifiedPolicyId =
        FullyQualifiedPolicyId(FullyQualifiedResourceId(ResourceTypeName(resourceTypeName), ResourceId(resourceId)), AccessPolicyName(policyName))
      fullyQualifiedPolicyId -> config.as[AccessPolicyMembershipRequest](s"$resourceTypeName.$resourceId.$policyName")
    }
    policies.toMap
  }

  /** Loads all the configs for the Sam App. All values defined in `src/main/resources/sam.conf` will take precedence over any other configs. In this way, we
    * can still use configs rendered by `firecloud-develop` that render to `config/sam.conf` if we want. To do so, you must render `config/sam.conf` and then do
    * not populate ENV variables for `src/main/resources/sam.conf`.
    *
    * The ENV variables that you need to populate can be found in `src/main/resources/sam.conf` variable substitutions. To run Sam locally, you can `source
    * env/local.env` and all required ENV variables will be populated with with default values.
    */
  def load: AppConfig = {
    // Start by parsing sam config files (like 'sam.conf') from wherever they live on the classpath
    val samConfig = ConfigFactory.parseResourcesAnySyntax("sam").resolve()
    // Load any other configs on the classpath following: https://github.com/lightbend/config#standard-behavior
    // This is where things like `src/main/resources/reference.conf` will get loaded
    val config = ConfigFactory.load()
    // Combine the two sets of configs.  If a value is defined in both sets of configs, the ones in `samConfig` will
    // take precedence over those in `config`
    val combinedConfig = samConfig.withFallback(config)
    AppConfig.readConfig(combinedConfig)
  }

  def readConfig(config: Config): AppConfig = {
    val googleEnabled = config.getAs[Boolean]("googleServices.googleEnabled").getOrElse(true)
    val googleConfigOption =
      if (googleEnabled) {
        for {
          googleServices <- config.getAs[GoogleServicesConfig]("googleServices")
        } yield GoogleConfig(
          googleServices,
          config.as[PetServiceAccountConfig]("petServiceAccount"),
          config.as[Option[Duration]]("coordinatedAdminSdkBackoffDuration")
        )
      } else None

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
      samDatabaseConfig = config.as[SamDatabaseConfig]("db"),
      blockedEmailDomains = config.as[Option[Seq[String]]]("blockedEmailDomains").getOrElse(Seq.empty),
      termsOfServiceConfig = config.as[TermsOfServiceConfig]("termsOfService"),
      oidcConfig = config.as[OidcConfig]("oidc"),
      adminConfig = config.as[AdminConfig]("admin"),
      azureServicesConfig = config.getAs[AzureServicesConfig]("azureServices"),
      prometheusConfig = config.as[PrometheusConfig]("prometheus"),
      janitorConfig = config.as[JanitorConfig]("janitor"),
      resourceAccessPolicies = config.as[Option[Map[FullyQualifiedPolicyId, AccessPolicyMembershipRequest]]]("resourceAccessPolicies").getOrElse(Map.empty)
    )
  }
}
