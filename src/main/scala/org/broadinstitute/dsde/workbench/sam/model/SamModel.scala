package org.broadinstitute.dsde.workbench.sam.model

import org.broadinstitute.dsde.workbench.model._
import spray.json.DefaultJsonProtocol

/**
  * Created by dvoet on 5/26/17.
  */

object SamJsonSupport {
  import DefaultJsonProtocol._
  import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._

  implicit val ResourceActionFormat = ValueObjectFormat(ResourceAction)

  implicit val ResourceRoleNameFormat = ValueObjectFormat(ResourceRoleName)

  implicit val ResourceRoleFormat = jsonFormat2(ResourceRole)

  implicit val ResourceTypeNameFormat = ValueObjectFormat(ResourceTypeName)

  implicit val ResourceTypeFormat = jsonFormat4(ResourceType)

  implicit val UserStatusDetailsFormat = jsonFormat2(UserStatusDetails)

  implicit val UserStatusFormat = jsonFormat2(UserStatus)

  implicit val AccessPolicyNameFormat = ValueObjectFormat(AccessPolicyName)

  implicit val ResourceIdFormat = ValueObjectFormat(ResourceId)

  implicit val ResourceFormat = jsonFormat2(Resource)

  implicit val AccessPolicyMembershipFormat = jsonFormat3(AccessPolicyMembership)

  implicit val AccessPolicyResponseEntryFormat = jsonFormat2(AccessPolicyResponseEntry)

  implicit val ResourceIdAndPolicyNameFormat = jsonFormat2(ResourceIdAndPolicyName)

  implicit val ResourceAndPolicyNameFormat = jsonFormat2(ResourceAndPolicyName)

}

object SamResourceActions {
  val readPolicies = ResourceAction("readpolicies")
  val alterPolicies = ResourceAction("alterpolicies")
  val delete = ResourceAction("delete")
}

case class UserStatusDetails(userSubjectId: WorkbenchUserId, userEmail: WorkbenchUserEmail) //for backwards compatibility to old API
case class UserStatus(userInfo: UserStatusDetails, enabled: Map[String, Boolean])

case class ResourceAction(value: String) extends ValueObject
case class ResourceRoleName(value: String) extends ValueObject
case class ResourceRole(roleName: ResourceRoleName, actions: Set[ResourceAction])

case class ResourceTypeName(value: String) extends ValueObject

case class Resource(resourceTypeName: ResourceTypeName, resourceId: ResourceId)
case class ResourceType(name: ResourceTypeName, actions: Set[ResourceAction], roles: Set[ResourceRole], ownerRoleName: ResourceRoleName)

case class ResourceId(value: String) extends ValueObject

case class ResourceIdAndPolicyName(resourceId: ResourceId, accessPolicyName: AccessPolicyName)
case class ResourceAndPolicyName(resource: Resource, accessPolicyName: AccessPolicyName) extends WorkbenchGroupIdentity
case class AccessPolicyName(value: String) extends ValueObject

/*
Note that AccessPolicy IS A group, does not have a group. This makes the ldap query to list all a user's policies
and thus resources much easier. We tried modeling with a "has a" relationship in code but a "is a" relationship in
ldap but it felt unnatural.
 */
case class AccessPolicy(id: ResourceAndPolicyName, members: Set[WorkbenchSubject], email: WorkbenchGroupEmail, roles: Set[ResourceRoleName], actions: Set[ResourceAction]) extends WorkbenchGroup
case class AccessPolicyMembership(memberEmails: Set[String], actions: Set[ResourceAction], roles: Set[ResourceRoleName])
case class AccessPolicyResponseEntry(policyName: AccessPolicyName, policy: AccessPolicyMembership)

case class BasicWorkbenchGroup(id: WorkbenchGroupName, members: Set[WorkbenchSubject], email: WorkbenchGroupEmail) extends WorkbenchGroup