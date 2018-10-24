package org.broadinstitute.dsde.workbench.sam.model

import monocle.macros.Lenses
import org.broadinstitute.dsde.workbench.model._
import spray.json.{DefaultJsonProtocol, JsValue, RootJsonFormat}

/**
  * Created by dvoet on 5/26/17.
  */


object SamJsonSupport {
  import DefaultJsonProtocol._
  import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._

  implicit val ResourceActionPatternFormat = jsonFormat3(ResourceActionPattern.apply)

  implicit val ResourceActionFormat = ValueObjectFormat(ResourceAction.apply)

  implicit val ResourceRoleNameFormat = ValueObjectFormat(ResourceRoleName.apply)

  implicit val ResourceRoleFormat = jsonFormat2(ResourceRole.apply)

  implicit val ResourceTypeNameFormat = ValueObjectFormat(ResourceTypeName.apply)

  implicit val ResourceTypeFormat = jsonFormat5(ResourceType.apply)

  implicit val UserStatusDetailsFormat = jsonFormat2(UserStatusDetails.apply)

  implicit val UserStatusFormat = jsonFormat2(UserStatus.apply)

  implicit val UserStatusInfoFormat = jsonFormat3(UserStatusInfo.apply)

  implicit val UserIdInfoFormat = jsonFormat3(UserIdInfo.apply)

  implicit val UserStatusDiagnosticsFormat = jsonFormat3(UserStatusDiagnostics.apply)

  implicit val AccessPolicyNameFormat = ValueObjectFormat(AccessPolicyName.apply)

  implicit val ResourceIdFormat = ValueObjectFormat(ResourceId.apply)

  implicit val ResourceIdentityFormat = jsonFormat2(FullyQualifiedResourceId.apply)

  implicit val AccessPolicyMembershipFormat = jsonFormat3(AccessPolicyMembership.apply)

  implicit val AccessPolicyResponseEntryFormat = jsonFormat3(AccessPolicyResponseEntry.apply)

  implicit val UserPolicyResponseFormat = jsonFormat5(UserPolicyResponse.apply)

  implicit val PolicyIdentityFormat = jsonFormat2(FullyQualifiedPolicyId.apply)

  implicit val ManagedGroupMembershipEntryFormat = jsonFormat3(ManagedGroupMembershipEntry.apply)

  implicit val ManagedGroupAccessInstructionsFormat = ValueObjectFormat(ManagedGroupAccessInstructions.apply)

  implicit val GroupSyncResponseFormat = jsonFormat2(GroupSyncResponse.apply)

  implicit val CreateResourceRequestFormat = jsonFormat3(CreateResourceRequest.apply)

}

object RootPrimitiveJsonSupport {
  implicit val rootBooleanJsonFormat: RootJsonFormat[Boolean] = new RootJsonFormat[Boolean] {
    import DefaultJsonProtocol.BooleanJsonFormat
    override def write(obj: Boolean): JsValue = BooleanJsonFormat.write(obj)
    override def read(json: JsValue): Boolean = BooleanJsonFormat.read(json)
  }
}

object SamResourceActions {
  val readPolicies = ResourceAction("read_policies")
  val alterPolicies = ResourceAction("alter_policies")
  val delete = ResourceAction("delete")
  val notifyAdmins = ResourceAction("notify_admins")
  val setAccessInstructions = ResourceAction("set_access_instructions")
  val setPublic = ResourceAction("set_public")
  val readAuthDomain = ResourceAction("read_auth_domain")

  def sharePolicy(policy: AccessPolicyName) = ResourceAction(s"share_policy::${policy.value}")
  def readPolicy(policy: AccessPolicyName) = ResourceAction(s"read_policy::${policy.value}")
  def setPublicPolicy(policy: AccessPolicyName) = ResourceAction(s"set_public::${policy.value}")
}

object SamResourceTypes {
  val resourceTypeAdminName = ResourceTypeName("resource_type_admin")
}

@Lenses case class UserStatusDetails(userSubjectId: WorkbenchUserId, userEmail: WorkbenchEmail) //for backwards compatibility to old API
@Lenses case class UserIdInfo(userSubjectId: WorkbenchUserId, userEmail: WorkbenchEmail, googleSubjectId: Option[GoogleSubjectId])
@Lenses case class UserStatus(userInfo: UserStatusDetails, enabled: Map[String, Boolean])
@Lenses case class UserStatusInfo(userSubjectId: String, userEmail: String, enabled: Boolean)
@Lenses case class UserStatusDiagnostics(enabled: Boolean, inAllUsersGroup: Boolean, inGoogleProxyGroup: Boolean)

@Lenses case class ResourceActionPattern(value: String, description: String, authDomainConstrainable: Boolean) {
  def matches(other: ResourceAction) = value.r.pattern.matcher(other.value).matches()
}
@Lenses case class ResourceAction(value: String) extends ValueObject
@Lenses case class ResourceRoleName(value: String) extends ValueObject
@Lenses case class ResourceRole(roleName: ResourceRoleName, actions: Set[ResourceAction])

@Lenses case class ResourceTypeName(value: String) extends ValueObject

@Lenses final case class FullyQualifiedResourceId(resourceTypeName: ResourceTypeName, resourceId: ResourceId)
@Lenses final case class Resource(resourceTypeName: ResourceTypeName, resourceId: ResourceId, authDomain: Set[WorkbenchGroupName]){
  val fullyQualifiedId = FullyQualifiedResourceId(resourceTypeName, resourceId)
}
@Lenses case class ResourceType(name: ResourceTypeName, actionPatterns: Set[ResourceActionPattern], roles: Set[ResourceRole], ownerRoleName: ResourceRoleName, reuseIds: Boolean = false) {
  // Ideally we'd just store this boolean in a lazy val, but this will upset the spray/akka json serializers
  // I can't imagine a scenario where we have enough action patterns that would make this def discernibly slow though
  def isAuthDomainConstrainable: Boolean = actionPatterns.exists(_.authDomainConstrainable)
}

@Lenses case class ResourceId(value: String) extends ValueObject
@Lenses final case class ResourceIdAndPolicyName(resourceId: ResourceId, accessPolicyName: AccessPolicyName)
@Lenses final case class UserPolicyResponse(resourceId: ResourceId, accessPolicyName: AccessPolicyName, authDomainGroups: Set[WorkbenchGroupName], missingAuthDomainGroups: Set[WorkbenchGroupName], public: Boolean)
@Lenses final case class FullyQualifiedPolicyId(resource: FullyQualifiedResourceId, accessPolicyName: AccessPolicyName) extends WorkbenchGroupIdentity {
  override def toString: String = s"${accessPolicyName.value}.${resource.resourceId.value}.${resource.resourceTypeName.value}"
}
@Lenses case class AccessPolicyName(value: String) extends ValueObject
@Lenses case class CreateResourceRequest(resourceId: ResourceId, policies: Map[AccessPolicyName, AccessPolicyMembership], authDomain: Set[WorkbenchGroupName])

/*
Note that AccessPolicy IS A group, does not have a group. This makes the ldap query to list all a user's policies
and thus resources much easier. We tried modeling with a "has a" relationship in code but a "is a" relationship in
ldap but it felt unnatural.
 */
@Lenses case class AccessPolicy(id: FullyQualifiedPolicyId, members: Set[WorkbenchSubject], email: WorkbenchEmail, roles: Set[ResourceRoleName], actions: Set[ResourceAction], public: Boolean) extends WorkbenchGroup
@Lenses case class AccessPolicyMembership(memberEmails: Set[WorkbenchEmail], actions: Set[ResourceAction], roles: Set[ResourceRoleName])
@Lenses case class AccessPolicyResponseEntry(policyName: AccessPolicyName, policy: AccessPolicyMembership, email: WorkbenchEmail)

@Lenses case class BasicWorkbenchGroup(id: WorkbenchGroupName, members: Set[WorkbenchSubject], email: WorkbenchEmail) extends WorkbenchGroup

@Lenses case class ManagedGroupMembershipEntry(groupName: ResourceId, role: AccessPolicyName, groupEmail: WorkbenchEmail)
@Lenses case class ManagedGroupAccessInstructions(value: String) extends ValueObject

@Lenses case class GroupSyncResponse(lastSyncDate: String, email: WorkbenchEmail)

object SamLenses{
  val resourceIdentityAccessPolicy = AccessPolicy.id composeLens FullyQualifiedPolicyId.resource
  val resourceTypeNameInAccessPolicy = resourceIdentityAccessPolicy composeLens FullyQualifiedResourceId.resourceTypeName
}