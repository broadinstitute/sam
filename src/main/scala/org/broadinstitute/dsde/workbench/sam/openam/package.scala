package org.broadinstitute.dsde.workbench.sam

import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader
import org.broadinstitute.dsde.workbench.sam.model._

/**
  * Created by mbemis on 6/2/17.
  */
package object openam {

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

}
