package org.broadinstitute.dsde.workbench.sam.model

/**
  * Created by mbemis on 5/30/17.
  */
object SamModels {

  case class Action(actionName: String)
  case class Role(roleName: String, actions: Set[Action])
  case class Resource(resourceType: String, roles: Set[Role])

}
