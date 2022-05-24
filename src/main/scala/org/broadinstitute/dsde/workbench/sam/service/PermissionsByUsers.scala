package org.broadinstitute.dsde.workbench.sam.service

import org.broadinstitute.dsde.workbench.model.WorkbenchSubject
import org.broadinstitute.dsde.workbench.sam.audit.AccessChange
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicy, AccessPolicyDescendantPermissions, ResourceAction, ResourceRoleName, ResourceTypeName}
import org.broadinstitute.dsde.workbench.sam.util.groupByFirstInPair

/**
  * Utility class for comparing collections of policies for use in audit logs
  * @param policies
  */
private[service] class PermissionsByUsers(policies: Iterable[AccessPolicy]) {
  private val memberRoles: Set[(WorkbenchSubject, ResourceRoleName)] = flattenMember(policies, _.roles)
  private val memberActions: Set[(WorkbenchSubject, ResourceAction)] = flattenMember(policies, _.actions)
  private val memberDescendantRoles: Set[(WorkbenchSubject, (ResourceTypeName, ResourceRoleName))] = flattenMemberDescendant(policies, _.roles)
  private val memberDescendantActions: Set[(WorkbenchSubject, (ResourceTypeName, ResourceAction))] = flattenMemberDescendant(policies, _.actions)

  /**
    * Returns the set of access changes if all the permissions in `other` are removed from `this`.
    */
  def removeAll(other: PermissionsByUsers): Set[AccessChange] = {
    val memberRolesDiff = groupByFirstInPair(this.memberRoles removedAll other.memberRoles)
    val memberActionsDiff = groupByFirstInPair(this.memberActions removedAll other.memberActions)
    val memberDescendantRolesDiff = groupByFirstInPair(this.memberDescendantRoles removedAll other.memberDescendantRoles)
    val memberDescendantActionsDiff = groupByFirstInPair(this.memberDescendantActions removedAll other.memberDescendantActions)
    compileDiff(memberRolesDiff, memberActionsDiff, memberDescendantRolesDiff, memberDescendantActionsDiff)
  }

  private def compileDiff(
      memberRolesDiff: Map[WorkbenchSubject, Iterable[ResourceRoleName]],
      memberActionsDiff: Map[WorkbenchSubject, Iterable[ResourceAction]],
      memberDescendantRolesDiff: Map[WorkbenchSubject, Iterable[(ResourceTypeName, ResourceRoleName)]],
      memberDescendantActionsDiff: Map[WorkbenchSubject, Iterable[(ResourceTypeName, ResourceAction)]]) = {
    for {
      member <- memberRolesDiff.keySet ++ memberActionsDiff.keySet ++ memberDescendantRolesDiff.keySet ++ memberDescendantActionsDiff.keySet
    } yield {
      AccessChange(
        member,
        memberRolesDiff.get(member),
        memberActionsDiff.get(member),
        memberDescendantRolesDiff.get(member).map(groupByFirstInPair),
        memberDescendantActionsDiff.get(member).map(groupByFirstInPair)
      )
    }
  }

  private def flattenMember[A](policies: Iterable[AccessPolicy], select: AccessPolicy => Set[A]): Set[(WorkbenchSubject, A)] =
    (for {
      policy <- policies
      member <- policy.members ++ maybeAllUsers(policy.public)
      selected <- select(policy)
    } yield (member, selected)).toSet

  private def flattenMemberDescendant[A](policies: Iterable[AccessPolicy], select: AccessPolicyDescendantPermissions => Set[A]): Set[(WorkbenchSubject, (ResourceTypeName, A))] =
    (for {
      policy <- policies
      member <- policy.members ++ maybeAllUsers(policy.public)
      descendantPermission <- policy.descendantPermissions
      selected <- select(descendantPermission)
    } yield (member, (descendantPermission.resourceType, selected))).toSet

  private def maybeAllUsers(public: Boolean) =
    if (public) {
      LazyList(CloudExtensions.allUsersGroupName)
    } else {
      LazyList.empty
    }
}
