package org.broadinstitute.dsde.workbench.sam.model

import monocle.macros.Lenses
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService.MangedGroupRoleName
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

  implicit val ResourceTypeNameFormat = ValueObjectFormat(ResourceTypeName.apply)

  implicit val ResourceRoleFormat = jsonFormat4(ResourceRole.apply)

  implicit val ResourceTypeFormat = jsonFormat5(ResourceType.apply)

  implicit val UserStatusDetailsFormat = jsonFormat2(UserStatusDetails.apply)

  implicit val UserStatusFormat = jsonFormat2(UserStatus.apply)

  implicit val UserStatusInfoFormat = jsonFormat4(UserStatusInfo.apply)

  implicit val UserIdInfoFormat = jsonFormat3(UserIdInfo.apply)

  implicit val TermsOfServiceAcceptanceFormat = ValueObjectFormat(TermsOfServiceAcceptance.apply)

  implicit val UserStatusDiagnosticsFormat = jsonFormat5(UserStatusDiagnostics.apply)

  implicit val AccessPolicyNameFormat = ValueObjectFormat(AccessPolicyName.apply)

  implicit val ResourceIdFormat = ValueObjectFormat(ResourceId.apply)

  implicit val FullyQualifiedResourceIdFormat = jsonFormat2(FullyQualifiedResourceId.apply)

  implicit val AccessPolicyDescendantPermissionsFormat = jsonFormat3(AccessPolicyDescendantPermissions.apply)

  implicit val PolicyIdentifiersFormat = jsonFormat4(PolicyIdentifiers.apply)

  implicit val AccessPolicyMembershipFormat = jsonFormat5(AccessPolicyMembership.apply)

  implicit val AccessPolicyResponseEntryFormat = jsonFormat3(AccessPolicyResponseEntry.apply)

  implicit val UserPolicyResponseFormat = jsonFormat5(UserPolicyResponse.apply)

  implicit val RolesAndActionsFormat = jsonFormat2(RolesAndActions.apply)

  implicit val UserResourcesResponseFormat = jsonFormat6(UserResourcesResponse.apply)

  implicit val PolicyIdentityFormat = jsonFormat2(FullyQualifiedPolicyId.apply)

  implicit val ManagedGroupMembershipEntryFormat = jsonFormat3(ManagedGroupMembershipEntry.apply)

  implicit val ManagedGroupAccessInstructionsFormat = ValueObjectFormat(ManagedGroupAccessInstructions.apply)

  implicit val GroupSyncResponseFormat = jsonFormat2(GroupSyncResponse.apply)

  implicit val CreateResourceRequestFormat = jsonFormat5(CreateResourceRequest.apply)

  implicit val CreateResourcePolicyResponseFormat = jsonFormat2(CreateResourcePolicyResponse.apply)

  implicit val CreateResourceResponseFormat = jsonFormat4(CreateResourceResponse.apply)
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
  val testAnyActionAccess = ResourceAction("test_any_action_access")
  val getParent = ResourceAction("get_parent")
  val setParent = ResourceAction("set_parent")
  val addChild = ResourceAction("add_child")
  val removeChild = ResourceAction("remove_child")
  val listChildren = ResourceAction("list_children")
  val createPet = ResourceAction("create-pet")

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
}

@Lenses final case class UserStatusDetails(userSubjectId: WorkbenchUserId, userEmail: WorkbenchEmail) //for backwards compatibility to old API
@Lenses final case class UserIdInfo(userSubjectId: WorkbenchUserId, userEmail: WorkbenchEmail, googleSubjectId: Option[GoogleSubjectId])
@Lenses final case class UserStatus(userInfo: UserStatusDetails, enabled: Map[String, Boolean])
@Lenses final case class UserStatusInfo(userSubjectId: String,
                                        userEmail: String,
                                        enabled: Boolean,
                                        adminEnabled: Boolean)
@Lenses final case class UserStatusDiagnostics(enabled: Boolean,
                                               inAllUsersGroup: Boolean,
                                               inGoogleProxyGroup: Boolean,
                                               tosAccepted: Option[Boolean],
                                               adminEnabled: Boolean)
@Lenses final case class TermsOfServiceAcceptance(value: String) extends ValueObject

@Lenses final case class ResourceActionPattern(value: String, description: String, authDomainConstrainable: Boolean) {
  def matches(other: ResourceAction) = value.r.pattern.matcher(other.value).matches()
}
@Lenses final case class ResourceAction(value: String) extends ValueObject
@Lenses case class ResourceRoleName(value: String) extends ValueObject
@Lenses final case class ResourceRole(roleName: ResourceRoleName,
                                      actions: Set[ResourceAction],
                                      includedRoles: Set[ResourceRoleName] = Set.empty,
                                      descendantRoles: Map[ResourceTypeName, Set[ResourceRoleName]] = Map.empty)

@Lenses final case class ResourceTypeName(value: String) extends ValueObject

@Lenses final case class FullyQualifiedResourceId(resourceTypeName: ResourceTypeName, resourceId: ResourceId)
@Lenses final case class Resource(resourceTypeName: ResourceTypeName, resourceId: ResourceId, authDomain: Set[WorkbenchGroupName], accessPolicies: Set[AccessPolicy] = Set.empty, parent: Option[FullyQualifiedResourceId] = None) {
  val fullyQualifiedId = FullyQualifiedResourceId(resourceTypeName, resourceId)
}
@Lenses final case class CreateResourceResponse(resourceTypeName: ResourceTypeName, resourceId: ResourceId, authDomain: Set[WorkbenchGroupName], accessPolicies: Set[CreateResourcePolicyResponse])
@Lenses final case class CreateResourcePolicyResponse(id: FullyQualifiedPolicyId, email: WorkbenchEmail)
@Lenses final case class ResourceType(
    name: ResourceTypeName,
    actionPatterns: Set[ResourceActionPattern],
    roles: Set[ResourceRole],
    ownerRoleName: ResourceRoleName,
    reuseIds: Boolean = false) {
  // Ideally we'd just store this boolean in a lazy val, but this will upset the spray/akka json serializers
  // I can't imagine a scenario where we have enough action patterns that would make this def discernibly slow though
  def isAuthDomainConstrainable: Boolean = actionPatterns.exists(_.authDomainConstrainable)
}

@Lenses final case class ResourceId(value: String) extends ValueObject
@Lenses final case class ResourceIdAndPolicyName(resourceId: ResourceId, accessPolicyName: AccessPolicyName)

/**
  * Response from AccessPolicyDAO.listUserResourcesWithRolesAndActions.
  * @param resourceId id of resource accessible to user
  * @param direct RolesAndActions assigned to the resource via policy directly on the resource
  * @param inherited RolesAndActions assigned to the resource via policy on resource's ancestor
  * @param public RolesAndActions assigned to the resource via public policy, could be direct or inherited
  */
@Lenses final case class ResourceIdWithRolesAndActions(resourceId: ResourceId, direct: RolesAndActions, inherited: RolesAndActions, public: RolesAndActions) {
  lazy val allRolesAndActions = direct ++ inherited // not necessary to include public as they should be included in both direct and inherited
}
@Lenses final case class RolesAndActions(roles: Set[ResourceRoleName], actions: Set[ResourceAction]) {
  def ++ (other: RolesAndActions): RolesAndActions = {
    RolesAndActions(this.roles ++ other.roles, this.actions ++ other.actions)
  }
}
object RolesAndActions {
  val empty = RolesAndActions(Set.empty, Set.empty)
  def fromRoles(roles: Set[ResourceRoleName]) = RolesAndActions(roles, Set.empty)
  def fromActions(actions: Set[ResourceAction]) = RolesAndActions(Set.empty, actions)
  def fromPolicy(accessPolicy: AccessPolicy) = RolesAndActions(accessPolicy.roles, accessPolicy.actions)
  def fromPolicyMembership(accessPolicy: AccessPolicyMembership) = RolesAndActions(accessPolicy.roles, accessPolicy.actions)
}
@Lenses final case class UserPolicyResponse(
    resourceId: ResourceId,
    accessPolicyName: AccessPolicyName,
    authDomainGroups: Set[WorkbenchGroupName],
    missingAuthDomainGroups: Set[WorkbenchGroupName],
    public: Boolean)
@Lenses final case class UserResourcesResponse(
    resourceId: ResourceId,
    direct: RolesAndActions,
    inherited: RolesAndActions,
    public: RolesAndActions,
    authDomainGroups: Set[WorkbenchGroupName],
    missingAuthDomainGroups: Set[WorkbenchGroupName])
@Lenses final case class FullyQualifiedPolicyId(resource: FullyQualifiedResourceId, accessPolicyName: AccessPolicyName) extends WorkbenchGroupIdentity {
  override def toString: String = s"${accessPolicyName.value}.${resource.resourceId.value}.${resource.resourceTypeName.value}"
}
@Lenses final case class PolicyIdentifiers(policyName: AccessPolicyName, policyEmail: WorkbenchEmail, resourceTypeName: ResourceTypeName, resourceId: ResourceId)
@Lenses case class AccessPolicyName(value: String) extends ValueObject
@Lenses final case class CreateResourceRequest(
                                                resourceId: ResourceId,
                                                policies: Map[AccessPolicyName, AccessPolicyMembership],
                                                authDomain: Set[WorkbenchGroupName],
                                                returnResource: Option[Boolean] = Some(false),
                                                parent: Option[FullyQualifiedResourceId] = None)

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
    public: Boolean)
    extends WorkbenchGroup

@Lenses final case class AccessPolicyDescendantPermissions(resourceType: ResourceTypeName, actions: Set[ResourceAction], roles: Set[ResourceRoleName])
// AccessPolicyMembership.memberPolicies is logically read-only; at some point in the future it could be lazy-loaded
// (via extra queries) based on the contents of memberEmails.
@Lenses final case class AccessPolicyMembership(memberEmails: Set[WorkbenchEmail],
                                                actions: Set[ResourceAction],
                                                roles: Set[ResourceRoleName],
                                                descendantPermissions: Option[Set[AccessPolicyDescendantPermissions]] = Option(Set.empty),
                                                memberPolicies: Option[Set[PolicyIdentifiers]] = Option(Set.empty)) {
  def getDescendantPermissions: Set[AccessPolicyDescendantPermissions] = descendantPermissions.getOrElse(Set.empty)
}
// AccessPolicyWithMembership is practically the same as AccessPolicyResponseEntry but the latter is used in api responses
// and the former is used at the DAO level so it seems better to keep them separate
@Lenses final case class AccessPolicyWithMembership(policyName: AccessPolicyName, membership: AccessPolicyMembership, email: WorkbenchEmail)
@Lenses final case class AccessPolicyResponseEntry(policyName: AccessPolicyName, policy: AccessPolicyMembership, email: WorkbenchEmail)

// Access Policy with no membership info to improve efficiency for calls that care about only the roles and actions of a policy, not the membership
@Lenses final case class AccessPolicyWithoutMembers(id: FullyQualifiedPolicyId, email: WorkbenchEmail, roles: Set[ResourceRoleName], actions: Set[ResourceAction], public: Boolean)

@Lenses final case class BasicWorkbenchGroup(id: WorkbenchGroupName, members: Set[WorkbenchSubject], email: WorkbenchEmail) extends WorkbenchGroup

@Lenses final case class ManagedGroupAndRole(groupName: WorkbenchGroupName, role: MangedGroupRoleName)
@Lenses final case class ManagedGroupMembershipEntry(groupName: ResourceId, role: ResourceRoleName, groupEmail: WorkbenchEmail)
@Lenses final case class ManagedGroupAccessInstructions(value: String) extends ValueObject

@Lenses final case class GroupSyncResponse(lastSyncDate: String, email: WorkbenchEmail)

final case class SamUser(id: WorkbenchUserId,
                         googleSubjectId: Option[GoogleSubjectId],
                         email: WorkbenchEmail,
                         azureB2CId: Option[AzureB2CId],
//                         acceptedToS: Option[Boolean], // None means ToS acceptance not required (disabled or grace period)
                         enabled: Boolean) {
//  val permittedToAccessTerra = acceptedToS.getOrElse(true) && enabled
  def toUserIdInfo = UserIdInfo(id, email, googleSubjectId)
}

object SamLenses {
  val resourceIdentityAccessPolicy = AccessPolicy.id composeLens FullyQualifiedPolicyId.resource
  val resourceTypeNameInAccessPolicy = resourceIdentityAccessPolicy composeLens FullyQualifiedResourceId.resourceTypeName
}
