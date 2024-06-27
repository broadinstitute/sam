package org.broadinstitute.dsde.workbench.sam.model

import monocle.macros.Lenses
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.api.{AccessPolicyMembershipRequest, AccessPolicyMembershipResponse, SamUser}
import spray.json.{DefaultJsonProtocol, JsValue, RootJsonFormat}

import java.time.Instant

/** Created by dvoet on 5/26/17.
  */

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
  val write = ResourceAction("write")
  val notifyAdmins = ResourceAction("notify_admins")
  val setAccessInstructions = ResourceAction("set_access_instructions")
  val setPublic = ResourceAction("set_public")
  val readAuthDomain = ResourceAction("read_auth_domain")
  val updateAuthDomain = ResourceAction("update_auth_domain")
  val testAnyActionAccess = ResourceAction("test_any_action_access")
  val getParent = ResourceAction("get_parent")
  val setParent = ResourceAction("set_parent")
  val createWithParent = ResourceAction("create_with_parent")
  val addChild = ResourceAction("add_child")
  val removeChild = ResourceAction("remove_child")
  val listChildren = ResourceAction("list_children")
  val createPet = ResourceAction("create-pet")
  val adminReadPolicies = ResourceAction("admin_read_policies")
  val adminAddMember = ResourceAction("admin_add_member")
  val adminRemoveMember = ResourceAction("admin_remove_member")
  val link = ResourceAction("link")
  val setManagedResourceGroup = ResourceAction("set_managed_resource_group")
  val adminReadSummaryInformation = ResourceAction("admin_read_summary_information")

  def sharePolicy(policy: AccessPolicyName) = ResourceAction(s"share_policy::${policy.value}")
  def readPolicy(policy: AccessPolicyName) = ResourceAction(s"read_policy::${policy.value}")
  def setPublicPolicy(policy: AccessPolicyName) = ResourceAction(s"set_public::${policy.value}")
  def testActionAccess(action: ResourceAction) = ResourceAction(s"test_action_access::${action.value}")
  def deletePolicy(policy: AccessPolicyName) = ResourceAction(s"delete_policy::${policy.value}")
}

object SamResourceTypes {
  val resourceTypeAdminName = ResourceTypeName("resource_type_admin")
  val workspaceName = ResourceTypeName("workspace")
  val googleProjectName = ResourceTypeName("google-project")
  val spendProfile = ResourceTypeName("spend-profile")
}

//for backwards compatibility to old API
@Lenses final case class UserStatusDetails(userSubjectId: WorkbenchUserId, userEmail: WorkbenchEmail)
object UserStatusDetails {
  def apply(samUser: SamUser): UserStatusDetails = UserStatusDetails(samUser.id, samUser.email)
}

@Lenses final case class UserIdInfo(userSubjectId: WorkbenchUserId, userEmail: WorkbenchEmail, googleSubjectId: Option[GoogleSubjectId])
@Lenses final case class UserStatus(userInfo: UserStatusDetails, enabled: Map[String, Boolean])
@Lenses final case class UserStatusInfo(userSubjectId: String, userEmail: String, enabled: Boolean, adminEnabled: Boolean)
@Lenses final case class UserStatusDiagnostics(
    enabled: Boolean,
    inAllUsersGroup: Boolean,
    inGoogleProxyGroup: Boolean,
    tosAccepted: Boolean,
    adminEnabled: Boolean
)
@Lenses final case class TermsOfServiceAcceptance(value: String) extends ValueObject

@Lenses final case class TermsOfServiceComplianceStatus(userId: WorkbenchUserId, userHasAcceptedLatestTos: Boolean, permitsSystemUsage: Boolean)

@Deprecated
@Lenses final case class OldTermsOfServiceDetails(
    isEnabled: Boolean,
    isGracePeriodEnabled: Boolean,
    currentVersion: String,
    userAcceptedVersion: Option[String]
)

@Lenses final case class TermsOfServiceDetails(
    latestAcceptedVersion: Option[String],
    acceptedOn: Option[Instant],
    permitsSystemUsage: Boolean,
    isCurrentVersion: Boolean
)
@Lenses final case class TermsOfServiceHistory(history: List[TermsOfServiceHistoryRecord])
@Lenses final case class TermsOfServiceHistoryRecord(action: String, version: String, timestamp: Instant)

@Lenses final case class ResourceActionPattern(value: String, description: String, authDomainConstrainable: Boolean) {
  def matches(other: ResourceAction) = value.r.pattern.matcher(other.value).matches()
}
@Lenses final case class ResourceAction(value: String) extends ValueObject
@Lenses case class ResourceRoleName(value: String) extends ValueObject
@Lenses final case class ResourceRole(
    roleName: ResourceRoleName,
    actions: Set[ResourceAction],
    includedRoles: Set[ResourceRoleName] = Set.empty,
    descendantRoles: Map[ResourceTypeName, Set[ResourceRoleName]] = Map.empty
)

@Lenses final case class ResourceTypeName(value: String) extends ValueObject {
  def isResourceTypeAdmin: Boolean = value == SamResourceTypes.resourceTypeAdminName.value
}

@Lenses final case class FullyQualifiedResourceId(resourceTypeName: ResourceTypeName, resourceId: ResourceId) {
  override def toString: String = s"$resourceTypeName/$resourceId"
}
@Lenses final case class Resource(
    resourceTypeName: ResourceTypeName,
    resourceId: ResourceId,
    authDomain: Set[WorkbenchGroupName],
    accessPolicies: Set[AccessPolicy] = Set.empty,
    parent: Option[FullyQualifiedResourceId] = None
) {
  val fullyQualifiedId = FullyQualifiedResourceId(resourceTypeName, resourceId)
}
@Lenses final case class CreateResourceResponse(
    resourceTypeName: ResourceTypeName,
    resourceId: ResourceId,
    authDomain: Set[WorkbenchGroupName],
    accessPolicies: Set[CreateResourcePolicyResponse]
)
@Lenses final case class CreateResourcePolicyResponse(id: FullyQualifiedPolicyId, email: WorkbenchEmail)
@Lenses final case class ResourceType(
    name: ResourceTypeName,
    actionPatterns: Set[ResourceActionPattern],
    roles: Set[ResourceRole],
    ownerRoleName: ResourceRoleName,
    reuseIds: Boolean = false,
    allowLeaving: Boolean = false
) {
  // Ideally we'd just store this boolean in a lazy val, but this will upset the spray/akka json serializers
  // I can't imagine a scenario where we have enough action patterns that would make this def discernibly slow though
  def isAuthDomainConstrainable: Boolean = actionPatterns.exists(_.authDomainConstrainable)
}

@Lenses final case class ResourceId(value: String) extends ValueObject
@Lenses final case class ResourceIdAndPolicyName(resourceId: ResourceId, accessPolicyName: AccessPolicyName)

/** Response from AccessPolicyDAO.listUserResourcesWithRolesAndActions.
  * @param resourceId
  *   id of resource accessible to user
  * @param direct
  *   RolesAndActions assigned to the resource via policy directly on the resource
  * @param inherited
  *   RolesAndActions assigned to the resource via policy on resource's ancestor
  * @param public
  *   RolesAndActions assigned to the resource via public policy, could be direct or inherited
  */
@Lenses final case class ResourceIdWithRolesAndActions(resourceId: ResourceId, direct: RolesAndActions, inherited: RolesAndActions, public: RolesAndActions) {
  lazy val allRolesAndActions = direct ++ inherited // not necessary to include public as they should be included in both direct and inherited
}
@Lenses final case class RolesAndActions(roles: Set[ResourceRoleName], actions: Set[ResourceAction]) {
  def ++(other: RolesAndActions): RolesAndActions =
    RolesAndActions(this.roles ++ other.roles, this.actions ++ other.actions)
}
object RolesAndActions {
  val empty = RolesAndActions(Set.empty, Set.empty)
  def fromRoles(roles: Set[ResourceRoleName]) = RolesAndActions(roles, Set.empty)
  def fromActions(actions: Set[ResourceAction]) = RolesAndActions(Set.empty, actions)
  def fromPolicy(accessPolicy: AccessPolicy) = RolesAndActions(accessPolicy.roles, accessPolicy.actions)
  def fromPolicyMembership(accessPolicy: AccessPolicyMembershipResponse) = RolesAndActions(accessPolicy.roles, accessPolicy.actions)
}
@Lenses final case class UserPolicyResponse(
    resourceId: ResourceId,
    accessPolicyName: AccessPolicyName,
    authDomainGroups: Set[WorkbenchGroupName],
    missingAuthDomainGroups: Set[WorkbenchGroupName],
    public: Boolean
)
@Lenses final case class UserResourcesResponse(
    resourceId: ResourceId,
    direct: RolesAndActions,
    inherited: RolesAndActions,
    public: RolesAndActions,
    authDomainGroups: Set[WorkbenchGroupName],
    missingAuthDomainGroups: Set[WorkbenchGroupName]
)
@Lenses final case class FullyQualifiedPolicyId(resource: FullyQualifiedResourceId, accessPolicyName: AccessPolicyName) extends WorkbenchGroupIdentity {
  override def toString: String = s"${accessPolicyName.value}.${resource.resourceId.value}.${resource.resourceTypeName.value}"
}

@Lenses case class PolicyIdentifiers(
    policyName: AccessPolicyName,
    resourceTypeName: ResourceTypeName,
    resourceId: ResourceId
)

@Lenses case class AccessPolicyName(value: String) extends ValueObject
@Lenses final case class CreateResourceRequest(
    resourceId: ResourceId,
    policies: Map[AccessPolicyName, AccessPolicyMembershipRequest],
    authDomain: Set[WorkbenchGroupName],
    returnResource: Option[Boolean] = Some(false),
    parent: Option[FullyQualifiedResourceId] = None
)

/*
Note that AccessPolicy IS A group because it was easier and more efficient to work with in ldap. In Postgres, it is
modeled with a "has a" relationship, but it retains the legacy "is a" relationship in code. Refactoring this into a
consistent "has a" relationship is tracked by this ticket: https://broadworkbench.atlassian.net/browse/CA-778
 */
@Lenses final case class AccessPolicy(
    id: FullyQualifiedPolicyId,
    members: Set[WorkbenchSubject],
    email: WorkbenchEmail,
    roles: Set[ResourceRoleName],
    actions: Set[ResourceAction],
    descendantPermissions: Set[AccessPolicyDescendantPermissions],
    public: Boolean
) extends WorkbenchGroup

@Lenses final case class AccessPolicyDescendantPermissions(resourceType: ResourceTypeName, actions: Set[ResourceAction], roles: Set[ResourceRoleName])

// AccessPolicyWithMembership is practically the same as AccessPolicyResponseEntry but the latter is used in api responses
// and the former is used at the DAO level so it seems better to keep them separate
@Lenses final case class AccessPolicyWithMembership(policyName: AccessPolicyName, membership: AccessPolicyMembershipResponse, email: WorkbenchEmail)
@Lenses final case class AccessPolicyResponseEntry(policyName: AccessPolicyName, policy: AccessPolicyMembershipResponse, email: WorkbenchEmail)

// Access Policy with no membership info to improve efficiency for calls that care about only the roles and actions of a policy, not the membership
@Lenses final case class AccessPolicyWithoutMembers(
    id: FullyQualifiedPolicyId,
    email: WorkbenchEmail,
    roles: Set[ResourceRoleName],
    actions: Set[ResourceAction],
    public: Boolean
)

@Lenses final case class BasicWorkbenchGroup(id: WorkbenchGroupName, members: Set[WorkbenchSubject], email: WorkbenchEmail) extends WorkbenchGroup
object BasicWorkbenchGroup {
  def apply(workbenchGroup: WorkbenchGroup): BasicWorkbenchGroup =
    workbenchGroup.id match {
      case wbg: WorkbenchGroupName => BasicWorkbenchGroup(wbg, workbenchGroup.members, workbenchGroup.email)
      case _ => throw new WorkbenchException(s"WorkbenchGroup ${workbenchGroup} cannot be converted to a BasicWorkbenchGroup")
    }
}

@Lenses final case class GroupSyncResponse(lastSyncDate: String, email: WorkbenchEmail)

@Lenses final case class SignedUrlRequest(bucketName: String, blobName: String, duration: Option[Long] = None, requesterPays: Option[Boolean] = Option(true))
@Lenses final case class RequesterPaysSignedUrlRequest(
    gsPath: String,
    duration: Option[Long] = None,
    requesterPaysProject: Option[String] = None
)

final case class SamUserTos(id: WorkbenchUserId, version: String, action: String, createdAt: Instant) {
  override def equals(other: Any): Boolean = other match {
    case userTos: SamUserTos =>
      this.id == userTos.id &&
      this.version == userTos.version &&
      this.action == userTos.action
    case _ => false
  }
  def toHistoryRecord: TermsOfServiceHistoryRecord = TermsOfServiceHistoryRecord(action, version, createdAt)
}
object SamLenses {
  val resourceIdentityAccessPolicy = AccessPolicy.id composeLens FullyQualifiedPolicyId.resource
  val resourceTypeNameInAccessPolicy = resourceIdentityAccessPolicy composeLens FullyQualifiedResourceId.resourceTypeName
}
