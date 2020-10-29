package org.broadinstitute.dsde.workbench.sam.config

import cats.data.NonEmptyList
import com.google.api.client.json.jackson2.JacksonFactory
import com.typesafe.config._
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.sam.model._
import GoogleServicesConfig.googleServicesConfigReader
import org.broadinstitute.dsde.workbench.google2.util.DistributedLockConfig

import scala.concurrent.duration.Duration

/**
  * Created by dvoet on 7/18/17.
  */
final case class AppConfig(
                            emailDomain: String,
                            directoryConfig: DirectoryConfig,
                            schemaLockConfig: SchemaLockConfig,
                            distributedLockConfig: DistributedLockConfig,
                            swaggerConfig: SwaggerConfig,
                            googleConfig: Option[GoogleConfig],
                            resourceTypes: Set[ResourceType],
                            liquibaseConfig: LiquibaseConfig,
                            identityConcentratorConfig: Option[IdentityConcentratorConfig],
                            blockedEmailDomains: Seq[String])

object AppConfig {
  implicit val swaggerReader: ValueReader[SwaggerConfig] = ValueReader.relative { config =>
    SwaggerConfig(
      config.getString("googleClientId"),
      config.getString("realm")
    )
  }

  def unquoteAndEscape(str: String): String = str.replace("\"", "").replaceAll("[:.+]+", "\"$0\"")

  implicit object resourceRoleReader extends ValueReader[ResourceRole] {
    override def read(config: Config, path: String): ResourceRole = {
      val uqPath = unquoteAndEscape(path)
      ResourceRole(
        ResourceRoleName(uqPath),
        config.as[Set[String]](s"$uqPath.roleActions").map(ResourceAction.apply)
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
        config.getBoolean(s"$uqPath.reuseIds")
      )
    }
  }

  implicit val cacheConfigReader: ValueReader[CacheConfig] = ValueReader.relative { config =>
    CacheConfig(config.getLong("maxEntries"), config.getDuration("timeToLive"))
  }

  implicit val directoryConfigReader: ValueReader[DirectoryConfig] = ValueReader.relative { config =>
    DirectoryConfig(
      config.getString("url"),
      config.getString("user"),
      config.getString("password"),
      config.getString("baseDn"),
      config.getString("enabledUsersGroupDn"),
      config.as[Option[Int]]("connectionPoolSize").getOrElse(15),
      config.as[Option[Int]]("backgroundConnectionPoolSize").getOrElse(5),
      config.as[Option[CacheConfig]]("memberOfCache").getOrElse(CacheConfig(100, java.time.Duration.ofMinutes(1))),
      config.as[Option[CacheConfig]]("resourceCache").getOrElse(CacheConfig(10000, java.time.Duration.ofHours(1)))
    )
  }

  val jsonFactory = JacksonFactory.getDefaultInstance

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

  implicit val schemaLockConfigReader: ValueReader[SchemaLockConfig] = ValueReader.relative { config =>
    SchemaLockConfig(
      config.getBoolean("lockSchemaOnBoot"),
      config.getInt("recheckTimeInterval"),
      config.getInt("maxTimeToWait"),
      config.getString("instanceId")
    )
  }

  implicit val distributedLockConfigReader: ValueReader[DistributedLockConfig] = ValueReader.relative { config =>
    val retryInterval = config.getDuration("retryInterval")

    DistributedLockConfig(
      Duration.fromNanos(retryInterval.toNanos),
      config.getInt("maxRetry")
    )
  }

  implicit val identityConcentratorConfigReader: ValueReader[IdentityConcentratorConfig] = ValueReader.relative { config =>
    IdentityConcentratorConfig(
      config.getString("baseUrl"),
      config.getInt("threadPoolSize")
    )
  }

  implicit val liquibaseConfigReader: ValueReader[LiquibaseConfig] = ValueReader.relative { config =>
    LiquibaseConfig(config.getString("changelog"), config.getBoolean("initWithLiquibase"))
  }

  def readConfig(config: Config): AppConfig = {
    val directoryConfig = config.as[DirectoryConfig]("directory")
    val googleConfigOption = for {
      googleServices <- config.getAs[GoogleServicesConfig]("googleServices")
    } yield GoogleConfig(googleServices, config.as[PetServiceAccountConfig]("petServiceAccount"))

    val schemaLockConfig = config.as[SchemaLockConfig]("schemaLock")
    val distributedLockConfig = config.as[DistributedLockConfig]("distributedLock")
    val swaggerConfig = config.as[SwaggerConfig]("swagger")
    // TODO - https://broadinstitute.atlassian.net/browse/GAWB-3603
    // This should JUST get the value from "emailDomain", but for now we're keeping the backwards compatibility code to
    // fall back to getting the "googleServices.appsDomain"
    val emailDomain = config.as[Option[String]]("emailDomain").getOrElse(config.getString("googleServices.appsDomain"))
    val resourceTypes = config.as[Map[String, ResourceType]]("resourceTypes").values.toSet
    val liquibaseConfig = config.as[LiquibaseConfig]("liquibase")
    val identityConcentratorConfig = config.as[Option[IdentityConcentratorConfig]]("identityConcentrator")

    val blockedEmailDomains = config.as[Option[Seq[String]]]("blockedEmailDomains").getOrElse(Seq.empty)

    AppConfig(emailDomain, directoryConfig, schemaLockConfig, distributedLockConfig, swaggerConfig, googleConfigOption, resourceTypes, liquibaseConfig, identityConcentratorConfig, blockedEmailDomains)
  }
}
