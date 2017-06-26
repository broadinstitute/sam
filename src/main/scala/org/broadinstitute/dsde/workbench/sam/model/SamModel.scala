package org.broadinstitute.dsde.workbench.sam.model

import org.broadinstitute.dsde.workbench.sam.WorkbenchException
import spray.json.DefaultJsonProtocol

/**
  * Created by dvoet on 5/26/17.
  */

object SamJsonSupport extends DefaultJsonProtocol {
  implicit def ResourceActionFormat = jsonFormat1(ResourceAction)

  implicit val ResourceRoleFormat = jsonFormat2(ResourceRole)

  implicit val ResourceTypeFormat = jsonFormat5(ResourceType)
}

sealed trait SamSubject
case class SamUserId(value: String) extends SamSubject
case class SamUserEmail(value: String)
case class SamUser(id: SamUserId, firstName: String, lastName: String, email: Option[SamUserEmail])

case class SamGroupName(value: String) extends SamSubject
case class SamGroup(name: SamGroupName, members: Set[SamSubject])

case class ResourceAction(actionName: String)

case class ResourceRole(roleName: String, actions: Set[ResourceAction])

case class ResourceType(name: String, actions: Set[String], roles: Set[ResourceRole], ownerRoleName: String, uuid: Option[String] = None)