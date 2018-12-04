package org.broadinstitute.dsde.workbench.sam.openam

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model._

/**
  * Created by dvoet on 6/26/17.
  */
trait AccessPolicyDAO {
  def createResourceType(resourceTypeName: ResourceTypeName): IO[ResourceTypeName]

  def createResource(resource: Resource): IO[Resource]
  def deleteResource(resource: FullyQualifiedResourceId): IO[Unit]
  def loadResourceAuthDomain(resource: FullyQualifiedResourceId): IO[Set[WorkbenchGroupName]]
  def listResourcesConstrainedByGroup(groupId: WorkbenchGroupIdentity): fs2.Stream[IO, Resource]

  def createPolicy(policy: AccessPolicy): IO[AccessPolicy]
  def deletePolicy(policy: FullyQualifiedPolicyId): IO[Unit]
  def loadPolicy(resourceAndPolicyName: FullyQualifiedPolicyId): IO[Option[AccessPolicy]]
  def overwritePolicyMembers(id: FullyQualifiedPolicyId, memberList: Set[WorkbenchSubject]): IO[Unit]
  def overwritePolicy(newPolicy: AccessPolicy): IO[AccessPolicy]
  def listPublicAccessPolicies(resourceTypeName: ResourceTypeName): fs2.Stream[IO, ResourceIdAndPolicyName]
  def listPublicAccessPolicies(resource: FullyQualifiedResourceId): fs2.Stream[IO, AccessPolicy]
  def listResourceWithAuthdomains(resourceTypeName: ResourceTypeName, resourceId: Set[ResourceId]): fs2.Stream[IO, Resource]
  def listAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId): IO[Set[ResourceIdAndPolicyName]]
  def listAccessPolicies(resource: FullyQualifiedResourceId): fs2.Stream[IO, AccessPolicy]
  def listAccessPoliciesForUser(resource: FullyQualifiedResourceId, user: WorkbenchUserId): fs2.Stream[IO, AccessPolicy]
  def listFlattenedPolicyMembers(policyId: FullyQualifiedPolicyId): fs2.Stream[IO, WorkbenchUser]
  def setPolicyIsPublic(policyId: FullyQualifiedPolicyId, isPublic: Boolean): IO[Unit]
}
