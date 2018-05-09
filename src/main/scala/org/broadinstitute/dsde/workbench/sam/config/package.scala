package org.broadinstitute.dsde.workbench.sam

import com.google.api.client.json.jackson2.JacksonFactory
import com.typesafe.config.ConfigException.WrongType
import com.typesafe.config._
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.sam.model._

/**
  * Created by dvoet on 7/18/17.
  */
package object config {
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
        config.as[Set[String]](s"$uqPath.roleActions").map(ResourceAction)
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

      // TODO: https://broadinstitute.atlassian.net/browse/GAWB-3589
      val basicActionPatterns: Set[ResourceActionPattern] = getActionPatterns(config, s"$uqPath.actionPatterns")
      val actionPatterns: Set[ResourceActionPattern] = getActionPatternObjects(config, s"$uqPath.actionPatternObjects") ++ basicActionPatterns

      ResourceType(
        ResourceTypeName(uqPath),
        actionPatterns,
        config.as[Map[String, ResourceRole]](s"$uqPath.roles").values.toSet,
        ResourceRoleName(config.getString(s"$uqPath.ownerRoleName")),
        config.getBoolean(s"$uqPath.reuseIds")
      )
    }
  }

  private def getActionPatternObjects(config: Config, path: String): Set[ResourceActionPattern] = {
    config.as[Map[String, ResourceActionPattern]](path).values.toSet
  }

  // See: https://broadinstitute.atlassian.net/browse/GAWB-3589
  // actionPatterns used to be defined as an Array[String], but they're transitioning to be Array[Object]
  // Once firecloud-develop is updated to specify ActionPatterns as Objects, then we can get rid of this code
  // Attempt to parse actionPatterns:
  // 1. as Option[Set[String]]
  // 2. IF that is the WrongType THEN try to parse as Map[String, ResourceActionPattern]]
  private def getActionPatterns(config: Config, path: String): Set[ResourceActionPattern] = {
    try {
      config.as[Option[Set[String]]](path) match {
        case Some(s) => s.map(ResourceActionPattern(_, "", false))
        case None => Set.empty
      }
    } catch {
      case ex: WrongType => getActionPatternObjects(config, path)
    }
  }

  implicit val directoryConfigReader: ValueReader[DirectoryConfig] = ValueReader.relative { config =>
    DirectoryConfig(
      config.getString("url"),
      config.getString("user"),
      config.getString("password"),
      config.getString("baseDn"),
      config.getString("enabledUsersGroupDn")
    )
  }

  val jsonFactory = JacksonFactory.getDefaultInstance

  implicit val googleServicesConfigReader: ValueReader[GoogleServicesConfig] = ValueReader.relative { config =>
    GoogleServicesConfig(
      config.getString("appName"),
      config.getString("appsDomain"),
      config.getString("pathToPem"),
      config.getString("serviceAccountClientId"),
      WorkbenchEmail(config.getString("serviceAccountClientEmail")),
      GoogleProject(config.getString("serviceAccountClientProject")),
      WorkbenchEmail(config.getString("subEmail")),
      WorkbenchEmail(config.getString("projectServiceAccount")),
      config.getString("groupSync.pubSubProject"),
      org.broadinstitute.dsde.workbench.util.toScalaDuration(config.getDuration("groupSync.pollInterval")),
      org.broadinstitute.dsde.workbench.util.toScalaDuration(config.getDuration("groupSync.pollJitter")),
      config.getString("groupSync.pubSubTopic"),
      config.getString("groupSync.pubSubSubscription"),
      config.getInt("groupSync.workerCount"),
      config.getString("notifications.topicName"),
      config.as[GoogleKeyCacheConfig]("googleKeyCache"),
      config.as[Option[String]]("resourceNamePrefix")
    )
  }

  implicit val petServiceAccountConfigReader: ValueReader[PetServiceAccountConfig] = ValueReader.relative { config =>
    PetServiceAccountConfig(
      GoogleProject(config.getString("googleProject")),
      config.as[Set[String]]("serviceAccountUsers").map(WorkbenchEmail)
    )
  }

  implicit val googleKeyCacheConfigReader: ValueReader[GoogleKeyCacheConfig] = ValueReader.relative { config =>
    GoogleKeyCacheConfig(
      GcsBucketName(config.getString("bucketName")),
      config.getInt("activeKeyMaxAge"),
      config.getInt("retiredKeyMaxAge"),
      config.getString("monitor.pubSubProject"),
      org.broadinstitute.dsde.workbench.util.toScalaDuration(config.getDuration("monitor.pollInterval")),
      org.broadinstitute.dsde.workbench.util.toScalaDuration(config.getDuration("monitor.pollJitter")),
      config.getString("monitor.pubSubTopic"),
      config.getString("monitor.pubSubSubscription"),
      config.getInt("monitor.workerCount")
    )
  }

  implicit val schemaLockConfig: ValueReader[SchemaLockConfig] = ValueReader.relative { config =>
    SchemaLockConfig(
      config.getBoolean("lockSchemaOnBoot"),
      config.getInt("recheckTimeInterval"),
      config.getInt("maxTimeToWait"),
      config.getString("instanceId"),
      config.getInt("schemaVersion")
    )
  }
}
