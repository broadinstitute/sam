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
  def createResourceType(resourceType: ResourceType, parentSpan: Span): IO[ResourceType]

  def createResource(resource: Resource, parentSpan: Span): IO[Resource]
  def deleteResource(resource: FullyQualifiedResourceId, parentSpan: Span): IO[Unit]
  def loadResourceAuthDomain(resource: FullyQualifiedResourceId, parentSpan: Span): IO[LoadResourceAuthDomainResult]
  def listResourcesConstrainedByGroup(groupId: WorkbenchGroupIdentity, parentSpan: Span): IO[Set[Resource]]
  def removeAuthDomainFromResource(resource: FullyQualifiedResourceId, parentSpan: Span): IO[Unit]

  def createPolicy(policy: AccessPolicy, parentSpan: Span): IO[AccessPolicy]
  def deletePolicy(policy: FullyQualifiedPolicyId, parentSpan: Span): IO[Unit]
  def loadPolicy(resourceAndPolicyName: FullyQualifiedPolicyId, parentSpan: Span): IO[Option[AccessPolicy]]
  def overwritePolicyMembers(id: FullyQualifiedPolicyId, memberList: Set[WorkbenchSubject], parentSpan: Span): IO[Unit]
  def overwritePolicy(newPolicy: AccessPolicy, parentSpan: Span): IO[AccessPolicy]
  def listPublicAccessPolicies(resourceTypeName: ResourceTypeName, parentSpan: Span): IO[Stream[ResourceIdAndPolicyName]]
  def listPublicAccessPolicies(resource: FullyQualifiedResourceId, parentSpan: Span): IO[Stream[AccessPolicyWithoutMembers]]
  def listResourcesWithAuthdomains(resourceTypeName: ResourceTypeName, resourceId: Set[ResourceId], parentSpan: Span): IO[Set[Resource]]
  def listResourceWithAuthdomains(resourceId: FullyQualifiedResourceId, parentSpan: Span): IO[Option[Resource]]
  def listAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId, parentSpan: Span): IO[Set[ResourceIdAndPolicyName]]
  def listAccessPolicies(resource: FullyQualifiedResourceId, parentSpan: Span): IO[Stream[AccessPolicy]]
  def listAccessPoliciesForUser(resource: FullyQualifiedResourceId, user: WorkbenchUserId, parentSpan: Span): IO[Set[AccessPolicyWithoutMembers]]
  def listFlattenedPolicyMembers(policyId: FullyQualifiedPolicyId, parentSpan: Span): IO[Set[WorkbenchUser]]
  def setPolicyIsPublic(policyId: FullyQualifiedPolicyId, isPublic: Boolean, parentSpan: Span): IO[Unit]

  def evictIsMemberOfCache(subject: WorkbenchSubject, parentSpan: Span): IO[Unit]
}


sealed abstract class LoadResourceAuthDomainResult
object LoadResourceAuthDomainResult {
  final case object ResourceNotFound extends LoadResourceAuthDomainResult
  final case object NotConstrained extends LoadResourceAuthDomainResult
  final case class Constrained(authDomain: NonEmptyList[WorkbenchGroupName]) extends LoadResourceAuthDomainResult
}
