package org.broadinstitute.dsde.workbench.sam

import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader
import org.broadinstitute.dsde.workbench.sam.model.{ResourceType, ResourceAction, ResourceRole}

/**
  * Created by mbemis on 6/2/17.
  */
package object openam {

  implicit val resourceRoleReader: ValueReader[ResourceRole] = ValueReader.relative { config =>
    ResourceRole(
      config.getString("roleName"),
      config.as[Set[String]]("roleActions").map(ResourceAction)
    )
  }

  implicit val resourceTypeReader: ValueReader[ResourceType] = ValueReader.relative { config =>
    ResourceType(
      config.getString("name"),
      config.getString("description"),
      config.as[Set[String]]("patterns"),
      config.as[Set[String]]("actions").map(_ -> false).toMap
    )
  }
}
