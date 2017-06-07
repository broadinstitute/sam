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

case class OpenAmResourceType(name: String, actions: Map[String, Boolean], patterns: Set[String])
case class ResourceType(resourceTypeName: String, roles: Set[ResourceRole], patterns: Set[String], uuid: Option[String] = None, ownerRoleName: String = "owner") {
  def actions: Map[String, Boolean] = roles.map(_.actions).flatMap(x => x.map(_.actionName -> false)).toMap
  def asOpenAm: OpenAmResourceType = OpenAmResourceType(this.resourceTypeName, this.actions, this.patterns)
}

case class OpenAmPolicySet(name: String, conditions: Set[String], applicationType: String,
                           resourceTypeUuids: Set[String], description: String, subjects: Set[String])
case class PolicySet(name: String, conditions: Set[String], resourceTypeUuids: Set[String]) {
  def asOpenAm: OpenAmPolicySet = OpenAmPolicySet(this.name, this.conditions, "iPlanetAMWebAgentService",
                                                  this.resourceTypeUuids, "This is a description to test.", Set.empty)
}