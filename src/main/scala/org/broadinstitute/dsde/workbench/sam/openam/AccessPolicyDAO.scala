package org.broadinstitute.dsde.workbench.sam.openam

import cats.data.NonEmptyList
import cats.effect.IO
import io.opencensus.trace.Span
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedResourceId, _}

/**
  * Created by dvoet on 6/26/17.
  */
trait AccessPolicyDAO {
  def createResourceType(resourceType: ResourceType): IO[ResourceType]

  def createResource(resource: Resource, span: Span = null): IO[Resource]
  def deleteResource(resource: FullyQualifiedResourceId): IO[Unit]
  def loadResourceAuthDomain(resource: FullyQualifiedResourceId, span: Span = null): IO[LoadResourceAuthDomainResult]
  def listResourcesConstrainedByGroup(groupId: WorkbenchGroupIdentity): IO[Set[Resource]]

  def createPolicy(policy: AccessPolicy, span: Span = null): IO[AccessPolicy]
  def deletePolicy(policy: FullyQualifiedPolicyId): IO[Unit]
  def loadPolicy(resourceAndPolicyName: FullyQualifiedPolicyId, span: Span = null): IO[Option[AccessPolicy]]
  def overwritePolicyMembers(id: FullyQualifiedPolicyId, memberList: Set[WorkbenchSubject]): IO[Unit]
  def overwritePolicy(newPolicy: AccessPolicy, span: Span = null): IO[AccessPolicy]
  def listPublicAccessPolicies(resourceTypeName: ResourceTypeName): IO[Stream[ResourceIdAndPolicyName]]
  def listPublicAccessPolicies(resource: FullyQualifiedResourceId, span: Span = null): IO[Stream[AccessPolicy]]
  def listResourcesWithAuthdomains(resourceTypeName: ResourceTypeName, resourceId: Set[ResourceId], span: Span = null): IO[Set[Resource]]
  def listResourceWithAuthdomains(resourceId: FullyQualifiedResourceId, span: Span = null): IO[Option[Resource]]
  def listResrouceTypeAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId, span: Span = null): IO[Set[ResourceIdAndPolicyName]]
  def listAccessPolicies(resource: FullyQualifiedResourceId, span: Span = null): IO[Stream[AccessPolicy]]
  def listAccessPoliciesForUser(resource: FullyQualifiedResourceId, user: WorkbenchUserId, span: Span = null): IO[Set[AccessPolicy]]
  def listFlattenedPolicyMembers(policyId: FullyQualifiedPolicyId): IO[Set[WorkbenchUser]]
  def setPolicyIsPublic(policyId: FullyQualifiedPolicyId, isPublic: Boolean): IO[Unit]
  def userMemberOfAnyPolicy(userId: WorkbenchUserId, policies: Set[AccessPolicy], parentSpan: Span = null): IO[Boolean]

  def evictIsMemberOfCache(subject: WorkbenchSubject): IO[Unit]
}


sealed abstract class LoadResourceAuthDomainResult
object LoadResourceAuthDomainResult {
  final case object ResourceNotFound extends LoadResourceAuthDomainResult
  final case object NotConstrained extends LoadResourceAuthDomainResult
  final case class Constrained(authDomain: NonEmptyList[WorkbenchGroupName]) extends LoadResourceAuthDomainResult
}