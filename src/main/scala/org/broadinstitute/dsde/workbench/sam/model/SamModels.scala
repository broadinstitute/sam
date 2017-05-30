package org.broadinstitute.dsde.workbench.sam.model

/**
  * Created by mbemis on 5/30/17.
  */
object SamModels {

  case class ResourceAction(actionName: String)
  case class ResourceRole(roleName: String, actions: Set[ResourceAction])
  case class Resource(resourceType: String, roles: Set[ResourceRole])

}
