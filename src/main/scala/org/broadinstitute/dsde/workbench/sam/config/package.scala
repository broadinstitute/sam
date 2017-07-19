package org.broadinstitute.dsde.workbench.sam

import net.ceedubs.ficus.readers.ValueReader
import org.broadinstitute.dsde.workbench.sam.model._
import net.ceedubs.ficus.Ficus._
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
      config.getString("baseDn")
    )
  }
}
