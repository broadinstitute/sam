package org.broadinstitute.dsde.workbench.sam

import java.io.StringReader

import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.json.jackson2.JacksonFactory
import net.ceedubs.ficus.readers.ValueReader
import org.broadinstitute.dsde.workbench.sam.model._
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.model.WorkbenchUserEmail
import org.broadinstitute.dsde.workbench.sam.config.DirectoryConfig

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

  implicit val resourceRoleReader: ValueReader[ResourceRole] = ValueReader.relative { config =>
    ResourceRole(
      ResourceRoleName(config.getString("roleName")),
      config.as[Set[String]]("roleActions").map(ResourceAction)
    )
  }

  implicit val resourceTypeReader: ValueReader[ResourceType] = ValueReader.relative { config =>
    ResourceType(
      ResourceTypeName(config.getString("name")),
      config.as[Set[String]]("actions").map(ResourceAction),
      config.as[Set[ResourceRole]]("roles"),
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

  implicit val googleDirectoryConfigReader: ValueReader[GoogleDirectoryConfig] = ValueReader.relative { config =>
    GoogleDirectoryConfig(
      GoogleClientSecrets.load(jsonFactory, new StringReader(config.getString("secrets"))),
      config.getString("pathToPem"),
      config.getString("appsDomain"),
      config.getString("appName"),
      config.getString("serviceProject")
    )
  }

  implicit val petServiceAccountConfigReader: ValueReader[PetServiceAccountConfig] = ValueReader.relative { config =>
    PetServiceAccountConfig(
      config.getString("googleProject"),
      config.as[Set[String]]("serviceAccountActors").map(WorkbenchUserEmail)
    )
  }
}
