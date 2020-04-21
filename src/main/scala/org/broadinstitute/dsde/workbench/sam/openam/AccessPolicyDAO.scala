package org.broadinstitute.dsde.workbench.sam.openam

import cats.data.NonEmptyList
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedResourceId, _}
import org.broadinstitute.dsde.workbench.sam.util.TraceContext

/**
  * Created by dvoet on 6/26/17.
  */
trait AccessPolicyDAO {
  def createResourceType(resourceType: ResourceType, traceContext: TraceContext): IO[ResourceType]

  def createResource(resource: Resource, traceContext: TraceContext): IO[Resource]
  def deleteResource(resource: FullyQualifiedResourceId, traceContext: TraceContext): IO[Unit]
  def loadResourceAuthDomain(resource: FullyQualifiedResourceId, traceContext: TraceContext): IO[LoadResourceAuthDomainResult]
  def listResourcesConstrainedByGroup(groupId: WorkbenchGroupIdentity, traceContext: TraceContext): IO[Set[Resource]]
  def removeAuthDomainFromResource(resource: FullyQualifiedResourceId, traceContext: TraceContext): IO[Unit]

  def createPolicy(policy: AccessPolicy, traceContext: TraceContext): IO[AccessPolicy]
  def deletePolicy(policy: FullyQualifiedPolicyId, traceContext: TraceContext): IO[Unit]
  def loadPolicy(resourceAndPolicyName: FullyQualifiedPolicyId, traceContext: TraceContext): IO[Option[AccessPolicy]]
  def overwritePolicyMembers(id: FullyQualifiedPolicyId, memberList: Set[WorkbenchSubject], traceContext: TraceContext): IO[Unit]
  def overwritePolicy(newPolicy: AccessPolicy, traceContext: TraceContext): IO[AccessPolicy]
  def listPublicAccessPolicies(resourceTypeName: ResourceTypeName, traceContext: TraceContext): IO[Stream[ResourceIdAndPolicyName]]
  def listPublicAccessPolicies(resource: FullyQualifiedResourceId, traceContext: TraceContext): IO[Stream[AccessPolicyWithoutMembers]]
  def listResourcesWithAuthdomains(resourceTypeName: ResourceTypeName, resourceId: Set[ResourceId], traceContext: TraceContext): IO[Set[Resource]]
  def listResourceWithAuthdomains(resourceId: FullyQualifiedResourceId, traceContext: TraceContext): IO[Option[Resource]]
  def listAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId, traceContext: TraceContext): IO[Set[ResourceIdAndPolicyName]]
  def listAccessPolicies(resource: FullyQualifiedResourceId, traceContext: TraceContext): IO[Stream[AccessPolicy]]
  def listAccessPoliciesForUser(resource: FullyQualifiedResourceId, user: WorkbenchUserId, traceContext: TraceContext): IO[Set[AccessPolicyWithoutMembers]]
  def listFlattenedPolicyMembers(policyId: FullyQualifiedPolicyId, traceContext: TraceContext): IO[Set[WorkbenchUser]]
  def setPolicyIsPublic(policyId: FullyQualifiedPolicyId, isPublic: Boolean, traceContext: TraceContext): IO[Unit]

  def evictIsMemberOfCache(subject: WorkbenchSubject, traceContext: TraceContext): IO[Unit]
}


sealed abstract class LoadResourceAuthDomainResult
object LoadResourceAuthDomainResult {
  final case object ResourceNotFound extends LoadResourceAuthDomainResult
  final case object NotConstrained extends LoadResourceAuthDomainResult
  final case class Constrained(authDomain: NonEmptyList[WorkbenchGroupName]) extends LoadResourceAuthDomainResult
}
