package org.broadinstitute.dsde.workbench.sam

import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ValueReader
import org.broadinstitute.dsde.workbench.sam.model.SamModels.{Resource, ResourceAction, ResourceRole}

/**
  * Created by dvoet on 5/26/17.
  */
package object openam {

  implicit val resourceRoleReader: ValueReader[ResourceRole] = ValueReader.relative { config =>
    ResourceRole(
      config.getString("roleName"),
      config.as[Set[String]]("roleActions").map(ResourceAction)
    )
  }

  implicit val resourceReader: ValueReader[Resource] = ValueReader.relative { config =>
    Resource(
      config.getString("name"),
      config.as[Set[ResourceRole]]("roles")
    )
  }
}
