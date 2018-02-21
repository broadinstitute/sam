package org.broadinstitute.dsde.workbench.sam

import java.io.StringReader
import java.util

import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.json.jackson2.JacksonFactory
import com.typesafe.config._
import net.ceedubs.ficus.readers.ValueReader
import org.broadinstitute.dsde.workbench.sam.model._
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig
import java.util.{Set => JSet, Map => JMap}

import scala.collection.JavaConverters._

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

  implicit object resourceRoleReader extends ValueReader[ResourceRole] {
    override def read(config: Config, path: String): ResourceRole = {
      ResourceRole(
        ResourceRoleName(path),
        config.as[Set[String]]("roleActions").map(ResourceAction)
      )
    }
  }

  implicit object resourceTypeReader extends ValueReader[ResourceType] {
    override def read(config: Config, path: String): ResourceType = {
    ResourceType(
      ResourceTypeName(path),
      config.as[Set[String]]("actionPatterns").map(ResourceActionPattern),
      config.as[Set[ResourceRole]]("roles"), //i think this won't work
      ResourceRoleName(config.getString("ownerRoleName"))
    )
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
      config.as[GoogleKeyCacheConfig]("googleKeyCache")
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
}
