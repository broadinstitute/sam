package org.broadinstitute.dsde.workbench.sam.model

import org.broadinstitute.dsde.workbench.model._
import spray.json.DefaultJsonProtocol

/**
  * Created by dvoet on 5/26/17.
  */

object SamJsonSupport {
  import DefaultJsonProtocol._
  import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._

  implicit val ResourceActionPatternFormat = jsonFormat3(ResourceActionPattern)

  implicit val ResourceActionFormat = ValueObjectFormat(ResourceAction)

  implicit val ResourceRoleNameFormat = ValueObjectFormat(ResourceRoleName)

  implicit val ResourceRoleFormat = jsonFormat2(ResourceRole)

  implicit val ResourceTypeNameFormat = ValueObjectFormat(ResourceTypeName)

  implicit val ResourceTypeFormat = jsonFormat5(ResourceType)

  implicit val UserStatusDetailsFormat = jsonFormat2(UserStatusDetails)

  implicit val UserStatusFormat = jsonFormat2(UserStatus)

  implicit val UserStatusInfoFormat = jsonFormat2(UserStatusInfo)

  implicit val userStatusDiagnosticsFormat = jsonFormat3(UserStatusDiagnostics)

  implicit val AccessPolicyNameFormat = ValueObjectFormat(AccessPolicyName)

  implicit val ResourceIdFormat = ValueObjectFormat(ResourceId)

  implicit val ResourceFormat = jsonFormat2(Resource)

  implicit val AccessPolicyMembershipFormat = jsonFormat3(AccessPolicyMembership)

  implicit val AccessPolicyResponseEntryFormat = jsonFormat3(AccessPolicyResponseEntry)

  implicit val ResourceIdAndPolicyNameFormat = jsonFormat2(ResourceIdAndPolicyName)

  implicit val ResourceAndPolicyNameFormat = jsonFormat2(ResourceAndPolicyName)

  implicit val ManagedGroupMembershipEntryFormat = jsonFormat3(ManagedGroupMembershipEntry)

  implicit val GroupSyncResponseFormat = jsonFormat1(GroupSyncResponse)

  implicit val CreateResourceRequestFormat = jsonFormat3(CreateResourceRequest)
}

object SamResourceActions {
  val readPolicies = ResourceAction("read_policies")
  val alterPolicies = ResourceAction("alter_policies")
  val delete = ResourceAction("delete")
  val notifyAdmins = ResourceAction("notify_admins")

  def sharePolicy(policy: AccessPolicyName) = ResourceAction(s"share_policy::${policy.value}")
  def readPolicy(policy: AccessPolicyName) = ResourceAction(s"read_policy::${policy.value}")
}

case class UserStatusDetails(userSubjectId: WorkbenchUserId, userEmail: WorkbenchEmail) //for backwards compatibility to old API
case class UserStatus(userInfo: UserStatusDetails, enabled: Map[String, Boolean])
case class UserStatusInfo(userInfo: UserStatusDetails, enabled: Boolean)
case class UserStatusDiagnostics(enabled: Boolean, inAllUsersGroup: Boolean, inGoogleProxyGroup: Boolean)

case class ResourceActionPattern(value: String, description: String, authDomainConstrainable: Boolean) {
  def matches(other: ResourceAction) = value.r.pattern.matcher(other.value).matches()
}
case class ResourceAction(value: String) extends ValueObject
case class ResourceRoleName(value: String) extends ValueObject
case class ResourceRole(roleName: ResourceRoleName, actions: Set[ResourceAction])

case class ResourceTypeName(value: String) extends ValueObject

case class Resource(resourceTypeName: ResourceTypeName, resourceId: ResourceId)
case class ResourceType(name: ResourceTypeName, actionPatterns: Set[ResourceActionPattern], roles: Set[ResourceRole], ownerRoleName: ResourceRoleName, reuseIds: Boolean = false) {
  // Ideally we'd just store this boolean in a lazy val, but this will upset the spray/akka json serializers
  // I can't imagine a scenario where we have enough action patterns that would make this def discernibly slow though
  def isAuthDomainConstrainable: Boolean = actionPatterns.map(_.authDomainConstrainable).fold(false)(_ || _)
}

case class ResourceId(value: String) extends ValueObject

case class ResourceIdAndPolicyName(resourceId: ResourceId, accessPolicyName: AccessPolicyName)
case class ResourceAndPolicyName(resource: Resource, accessPolicyName: AccessPolicyName) extends WorkbenchGroupIdentity {
  override def toString: String = s"${accessPolicyName.value}.${resource.resourceId.value}.${resource.resourceTypeName.value}"
}
case class AccessPolicyName(value: String) extends ValueObject
case class CreateResourceRequest(resourceId: ResourceId, policies: Map[AccessPolicyName, AccessPolicyMembership], authDomain: Set[WorkbenchGroupName])

/*
Note that AccessPolicy IS A group, does not have a group. This makes the ldap query to list all a user's policies
and thus resources much easier. We tried modeling with a "has a" relationship in code but a "is a" relationship in
ldap but it felt unnatural.
 */
case class AccessPolicy(id: ResourceAndPolicyName, members: Set[WorkbenchSubject], email: WorkbenchEmail, roles: Set[ResourceRoleName], actions: Set[ResourceAction]) extends WorkbenchGroup
case class AccessPolicyMembership(memberEmails: Set[WorkbenchEmail], actions: Set[ResourceAction], roles: Set[ResourceRoleName])
case class AccessPolicyResponseEntry(policyName: AccessPolicyName, policy: AccessPolicyMembership, email: WorkbenchEmail)

case class BasicWorkbenchGroup(id: WorkbenchGroupName, members: Set[WorkbenchSubject], email: WorkbenchEmail) extends WorkbenchGroup

case class ManagedGroupMembershipEntry(groupName: ResourceId, role: AccessPolicyName, groupEmail: WorkbenchEmail)

case class GroupSyncResponse(lastSyncDate: String)
