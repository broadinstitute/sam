package org.broadinstitute.dsde.workbench.sam.service

import org.broadinstitute.dsde.workbench.model.WorkbenchSubject
import org.broadinstitute.dsde.workbench.sam.audit.AccessChange
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicy, ResourceAction, ResourceRoleName, ResourceTypeName}
import org.broadinstitute.dsde.workbench.sam.util.groupByFirstInPair

/**
  * Utility class for comparing collections of policies for use in audit logs
  * @param policies
  */
private[service] class PermissionsByUsers(policies: Iterable[AccessPolicy]) {
  private val memberRoles: Set[(WorkbenchSubject, ResourceRoleName)] = flattenMemberRoles(policies)
  private val memberActions: Set[(WorkbenchSubject, ResourceAction)] = flattenMemberActions(policies)
  private val memberDescendantRoles: Set[(WorkbenchSubject, (ResourceTypeName, ResourceRoleName))] = flattenMemberDependantRoles(policies)
  private val memberDescendantActions: Set[(WorkbenchSubject, (ResourceTypeName, ResourceAction))] = flattenMemberDependantActions(policies)

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

  private def flattenMemberRoles(policies: Iterable[AccessPolicy]): Set[(WorkbenchSubject, ResourceRoleName)] =
    (for {
      policy <- policies
      member <- policy.members ++ maybeAllUsers(policy.public)
      role <- policy.roles
    } yield (member, role)).toSet

  private def flattenMemberActions(policies: Iterable[AccessPolicy]): Set[(WorkbenchSubject, ResourceAction)] =
    (for {
      policy <- policies
      member <- policy.members ++ maybeAllUsers(policy.public)
      action <- policy.actions
    } yield (member, action)).toSet

  private def flattenMemberDependantRoles(policies: Iterable[AccessPolicy]): Set[(WorkbenchSubject, (ResourceTypeName, ResourceRoleName))] =
    (for {
      policy <- policies
      member <- policy.members ++ maybeAllUsers(policy.public)
      descendantPermission <- policy.descendantPermissions
      role <- descendantPermission.roles
    } yield (member, (descendantPermission.resourceType, role))).toSet

  private def flattenMemberDependantActions(policies: Iterable[AccessPolicy]): Set[(WorkbenchSubject, (ResourceTypeName, ResourceAction))] =
    (for {
      policy <- policies
      member <- policy.members ++ maybeAllUsers(policy.public)
      descendantPermission <- policy.descendantPermissions
      action <- descendantPermission.actions
    } yield (member, (descendantPermission.resourceType, action))).toSet

  private def maybeAllUsers(public: Boolean) =
    if (public) {
      LazyList(CloudExtensions.allUsersGroupName)
    } else {
      LazyList.empty
    }
}
