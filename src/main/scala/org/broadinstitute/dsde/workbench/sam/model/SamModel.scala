package org.broadinstitute.dsde.workbench.sam.model

import org.broadinstitute.dsde.workbench.model._
import spray.json.DefaultJsonProtocol

/**
  * Created by dvoet on 5/26/17.
  */

object SamJsonSupport {
  import DefaultJsonProtocol._
  import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._

  implicit val ResourceActionPatternFormat = ValueObjectFormat(ResourceActionPattern)

  implicit val ResourceActionFormat = ValueObjectFormat(ResourceAction)

  implicit val ResourceRoleNameFormat = ValueObjectFormat(ResourceRoleName)

  implicit val ResourceRoleFormat = jsonFormat2(ResourceRole)

  implicit val ResourceTypeNameFormat = ValueObjectFormat(ResourceTypeName)

  implicit val ResourceTypeFormat = jsonFormat5(ResourceType)

  implicit val UserStatusDetailsFormat = jsonFormat2(UserStatusDetails)

  implicit val UserStatusFormat = jsonFormat2(UserStatus)

  implicit val AccessPolicyNameFormat = ValueObjectFormat(AccessPolicyName)

  implicit val ResourceIdFormat = ValueObjectFormat(ResourceId)

  implicit val ResourceFormat = jsonFormat2(Resource)

  implicit val AccessPolicyMembershipFormat = jsonFormat3(AccessPolicyMembership)

  implicit val AccessPolicyResponseEntryFormat = jsonFormat3(AccessPolicyResponseEntry)

  implicit val ResourceIdAndPolicyNameFormat = jsonFormat2(ResourceIdAndPolicyName)

  implicit val ResourceAndPolicyNameFormat = jsonFormat2(ResourceAndPolicyName)

  implicit val ManagedGroupMembershipEntryFormat = jsonFormat3(ManagedGroupMembershipEntry)

  implicit val GroupSyncResponseFormat = jsonFormat1(GroupSyncResponse)

  implicit val CreateResourceRequestFormat = jsonFormat2(CreateResourceRequest)
}

object SamResourceActions {
  val readPolicies = ResourceAction("read_policies")
  val alterPolicies = ResourceAction("alter_policies")
  val delete = ResourceAction("delete")

  def sharePolicy(policy: AccessPolicyName) = ResourceAction(s"share_policy::${policy.value}")
  def readPolicy(policy: AccessPolicyName) = ResourceAction(s"read_policy::${policy.value}")
}

object SamResourceActionPatterns {
  val readPolicies = ResourceActionPattern("read_policies")
  val alterPolicies = ResourceActionPattern("alter_policies")
  val delete = ResourceActionPattern("delete")
  
  val sharePolicy = ResourceActionPattern("share_policy::.+")
  val readPolicy = ResourceActionPattern("read_policy::.+")
}

case class UserStatusDetails(userSubjectId: WorkbenchUserId, userEmail: WorkbenchEmail) //for backwards compatibility to old API
case class UserStatus(userInfo: UserStatusDetails, enabled: Map[String, Boolean])

case class ResourceActionPattern(value: String) extends ValueObject {
  lazy val regex = value.r
  def matches(other: ResourceAction) = regex.pattern.matcher(other.value).matches()
}
case class ResourceAction(value: String) extends ValueObject
case class ResourceRoleName(value: String) extends ValueObject
case class ResourceRole(roleName: ResourceRoleName, actions: Set[ResourceAction])

case class ResourceTypeName(value: String) extends ValueObject

case class Resource(resourceTypeName: ResourceTypeName, resourceId: ResourceId)
case class ResourceType(name: ResourceTypeName, actionPatterns: Set[ResourceActionPattern], roles: Set[ResourceRole], ownerRoleName: ResourceRoleName, reuseIds: Boolean = false)

case class ResourceId(value: String) extends ValueObject

case class ResourceIdAndPolicyName(resourceId: ResourceId, accessPolicyName: AccessPolicyName)
case class ResourceAndPolicyName(resource: Resource, accessPolicyName: AccessPolicyName) extends WorkbenchGroupIdentity {
  override def toString: String = s"${accessPolicyName.value}.${resource.resourceId.value}.${resource.resourceTypeName.value}"
}
case class AccessPolicyName(value: String) extends ValueObject
case class CreateResourceRequest(resourceId: ResourceId, policies: Map[AccessPolicyName, AccessPolicyMembership])

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
