package org.broadinstitute.dsde.workbench.sam.openam

import cats.data.NonEmptyList
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedResourceId, _}

/**
  * Created by dvoet on 6/26/17.
  */
trait AccessPolicyDAO {
  def createResourceType(resourceType: ResourceType): IO[ResourceType]

  def createResource(resource: Resource): IO[Resource]
  def deleteResource(resource: FullyQualifiedResourceId): IO[Unit]
  def loadResourceAuthDomain(resource: FullyQualifiedResourceId): IO[LoadResourceAuthDomainResult]
  def listResourcesConstrainedByGroup(groupId: WorkbenchGroupIdentity): IO[Set[Resource]]
  def removeAuthDomainFromResource(resource: FullyQualifiedResourceId): IO[Unit]

  def createPolicy(policy: AccessPolicy): IO[AccessPolicy]
  def deletePolicy(policy: FullyQualifiedPolicyId): IO[Unit]
  def loadPolicy(resourceAndPolicyName: FullyQualifiedPolicyId): IO[Option[AccessPolicy]]
  def overwritePolicyMembers(id: FullyQualifiedPolicyId, memberList: Set[WorkbenchSubject]): IO[Unit]
  def overwritePolicy(newPolicy: AccessPolicy): IO[AccessPolicy]
  def listPublicAccessPolicies(resourceTypeName: ResourceTypeName): IO[Stream[ResourceIdAndPolicyName]]
  def listPublicAccessPolicies(resource: FullyQualifiedResourceId): IO[Stream[AccessPolicy]]
  def listResourcesWithAuthdomains(resourceTypeName: ResourceTypeName, resourceId: Set[ResourceId]): IO[Set[Resource]]
  def listResourceWithAuthdomains(resourceId: FullyQualifiedResourceId): IO[Option[Resource]]
  def listAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId): IO[Set[ResourceIdAndPolicyName]]
  def listAccessPolicies(resource: FullyQualifiedResourceId): IO[Stream[AccessPolicy]]
  def listAccessPoliciesForUser(resource: FullyQualifiedResourceId, user: WorkbenchUserId): IO[Set[AccessPolicy]]
  def listFlattenedPolicyMembers(policyId: FullyQualifiedPolicyId): IO[Set[WorkbenchUser]]
  def setPolicyIsPublic(policyId: FullyQualifiedPolicyId, isPublic: Boolean): IO[Unit]

  def evictIsMemberOfCache(subject: WorkbenchSubject): IO[Unit]
}


sealed abstract class LoadResourceAuthDomainResult
object LoadResourceAuthDomainResult {
  final case object ResourceNotFound extends LoadResourceAuthDomainResult
  final case object NotConstrained extends LoadResourceAuthDomainResult
  final case class Constrained(authDomain: NonEmptyList[WorkbenchGroupName]) extends LoadResourceAuthDomainResult
}