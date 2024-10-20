package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.data.NonEmptyList
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.api.{AccessPolicyMembershipResponse, SamUser}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

/** Created by dvoet on 6/26/17.
  */
trait AccessPolicyDAO {
  def upsertResourceTypes(resourceTypes: Set[ResourceType], samRequestContext: SamRequestContext): IO[Set[ResourceTypeName]]

  def loadResourceTypes(resourceTypeNames: Set[ResourceTypeName], samRequestContext: SamRequestContext): IO[Set[ResourceType]]

  def createResourceType(resourceType: ResourceType, samRequestContext: SamRequestContext): IO[ResourceType]

  def createResource(resource: Resource, samRequestContext: SamRequestContext): IO[Resource]

  /** Deletes a resource. If leaveTombStone is true, a record of the resource will be kept in the database to prevent the resource name from being reused. If
    * leaveTombStone is false, the resource will be completely removed from the database.
    */
  def deleteResource(resource: FullyQualifiedResourceId, leaveTombStone: Boolean, samRequestContext: SamRequestContext): IO[Unit]

  def loadResourceAuthDomain(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[LoadResourceAuthDomainResult]

  def addResourceAuthDomain(
      resource: FullyQualifiedResourceId,
      authDomains: Set[WorkbenchGroupName],
      samRequestContext: SamRequestContext
  ): IO[Unit]

  /** Lists policies on resources that are constrained by the given group. If relevantMembers is provided, only policies that contain any of the relevantMembers
    * (directly or indirectly) will be returned.
    * @param groupId
    *   the group to constrain by
    * @param relevantMembers
    *   if provided, only policies that contain any of the relevantMembers (directly or indirectly) will be returned, if empty, all policies will be returned
    */
  def listSyncedAccessPolicyIdsOnResourcesConstrainedByGroup(
      groupId: WorkbenchGroupIdentity,
      relevantMembers: Set[WorkbenchSubject],
      samRequestContext: SamRequestContext
  ): IO[Set[FullyQualifiedPolicyId]]

  def createPolicy(policy: AccessPolicy, samRequestContext: SamRequestContext): IO[AccessPolicy]

  def deletePolicy(policy: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Unit]

  def loadPolicy(resourceAndPolicyName: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Option[AccessPolicy]]

  def loadPolicyMembership(policyId: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Option[AccessPolicyMembershipResponse]]

  def listAccessPolicyMemberships(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[LazyList[AccessPolicyWithMembership]]

  def overwritePolicyMembers(id: FullyQualifiedPolicyId, memberList: Set[WorkbenchSubject], samRequestContext: SamRequestContext): IO[Unit]

  def overwritePolicy(newPolicy: AccessPolicy, samRequestContext: SamRequestContext): IO[AccessPolicy]

  @deprecated("listing policies for resource type removed", since = "ResourceRoutes v2")
  def listPublicAccessPolicies(resourceTypeName: ResourceTypeName, samRequestContext: SamRequestContext): IO[LazyList[ResourceIdAndPolicyName]]

  @deprecated("listing policies for resource type removed", since = "ResourceRoutes v2")
  def listPublicAccessPolicies(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[LazyList[AccessPolicyWithoutMembers]]

  def listResourcesWithAuthdomains(resourceTypeName: ResourceTypeName, resourceId: Set[ResourceId], samRequestContext: SamRequestContext): IO[Set[Resource]]

  def listResourceWithAuthDomains(resourceId: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Option[Resource]]

  def listResourcesUsingAuthDomain(
      authDomainGroupName: WorkbenchGroupName,
      samRequestContext: SamRequestContext
  ): IO[Set[FullyQualifiedResourceId]]

  //  @deprecated("listing policies for resource type removed", since = "ResourceRoutes v2")
  def listAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[ResourceIdAndPolicyName]]

  def listAccessPolicies(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[LazyList[AccessPolicy]]

  @deprecated("listing policies for resource type removed", since = "ResourceRoutes v2")
  def listAccessPoliciesForUser(
      resource: FullyQualifiedResourceId,
      user: WorkbenchUserId,
      samRequestContext: SamRequestContext
  ): IO[Set[AccessPolicyWithoutMembers]]

  def listUserResourceActions(resourceId: FullyQualifiedResourceId, user: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[ResourceAction]]

  def listUserResourceRoles(resourceId: FullyQualifiedResourceId, user: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[ResourceRoleName]]

  def listFlattenedPolicyMembers(policyId: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Set[SamUser]]

  def setPolicyIsPublic(policyId: FullyQualifiedPolicyId, isPublic: Boolean, samRequestContext: SamRequestContext): IO[Boolean]

  def getResourceParent(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Option[FullyQualifiedResourceId]]

  def setResourceParent(childResource: FullyQualifiedResourceId, parentResource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit]

  def deleteResourceParent(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Boolean]

  def listResourceChildren(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Set[FullyQualifiedResourceId]]

  def listUserResourcesWithRolesAndActions(
      resourceTypeName: ResourceTypeName,
      userId: WorkbenchUserId,
      samRequestContext: SamRequestContext
  ): IO[Iterable[ResourceIdWithRolesAndActions]]

  /** Utility function that takes a bunch of ResourceIdWithRolesAndActions, probably more than one for a give resource id, and aggregates all the ones with same
    * resource id together.
    *
    * @param fragmentedRolesAndActions
    * @return
    */
  protected def aggregateByResource(fragmentedRolesAndActions: Iterable[ResourceIdWithRolesAndActions]): Iterable[ResourceIdWithRolesAndActions] =
    fragmentedRolesAndActions.groupBy(_.resourceId).map { case (resourceId, rowsForResource) =>
      rowsForResource.reduce { (left, right) =>
        ResourceIdWithRolesAndActions(resourceId, left.direct ++ right.direct, left.inherited ++ right.inherited, left.public ++ right.public)
      }
    }

  def filterResources(
      samUserId: WorkbenchUserId,
      resourceTypeNames: Set[ResourceTypeName],
      policies: Set[AccessPolicyName],
      roles: Set[ResourceRoleName],
      actions: Set[ResourceAction],
      includePublic: Boolean,
      samRequestContext: SamRequestContext
  ): IO[Seq[FilterResourcesResult]]

  def checkPolicyGroupsInUse(resourceId: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[List[Map[String, String]]]
}

sealed abstract class LoadResourceAuthDomainResult
object LoadResourceAuthDomainResult {
  final case object ResourceNotFound extends LoadResourceAuthDomainResult
  final case object NotConstrained extends LoadResourceAuthDomainResult
  final case class Constrained(authDomain: NonEmptyList[WorkbenchGroupName]) extends LoadResourceAuthDomainResult
}
