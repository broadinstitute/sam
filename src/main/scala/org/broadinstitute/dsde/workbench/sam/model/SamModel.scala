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

case class ResourceType(
                               name: String,
                               description: String,
                               patterns: Set[String],
                               actions: Map[String, Boolean])

case class PolicySet(name: String, conditions: Set[String], resourceTypeUuids: Set[String]) {
  def asOpenAm: OpenAmPolicySet = OpenAmPolicySet(this.name, this.conditions, "iPlanetAMWebAgentService",
                                                  this.resourceTypeUuids, "This is a description to test.", Set.empty)
}