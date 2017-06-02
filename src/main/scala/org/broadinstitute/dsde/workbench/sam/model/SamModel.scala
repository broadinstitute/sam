package org.broadinstitute.dsde.workbench.sam.model

/**
  * Created by dvoet on 5/26/17.
  */
sealed trait SamSubject
case class SamUserId(value: String) extends SamSubject
case class SamUserEmail(value: String)
case class SamUser(id: SamUserId, firstName: String, lastName: String, email: Option[SamUserEmail])

case class SamGroupName(value: String) extends SamSubject
case class SamGroup(name: SamGroupName, members: Set[SamSubject])

case class ResourceAction(actionName: String)
case class ResourceRole(roleName: String, actions: Set[ResourceAction])

case class OpenAmResourceType(name: String, actions: Map[String, Boolean], patterns: Seq[String])
case class ResourceType(resourceTypeName: String, roles: Set[ResourceRole]) {
  def actions: Map[String, Boolean] = roles.map(_.actions).flatMap(x => x.map(_.actionName -> true)).toMap
  def asOpenAm: OpenAmResourceType = OpenAmResourceType(this.resourceTypeName, this.actions, Seq.empty)
}
