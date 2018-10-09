package org.broadinstitute.dsde.workbench.sam.openam

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model._

import scala.concurrent.Future

/**
  * Created by dvoet on 6/26/17.
  */
trait AccessPolicyDAO {
  def createResourceType(resourceTypeName: ResourceTypeName): IO[ResourceTypeName]

  def createResource(resource: Resource): IO[Resource]
  def deleteResource(resource: Resource): IO[Unit]
  def loadResourceAuthDomain(resource: Resource): IO[Set[WorkbenchGroupName]]
  def listResourcesConstrainedByGroup(groupId: WorkbenchGroupIdentity): Future[Set[Resource]]

  def createPolicy(policy: AccessPolicy): IO[AccessPolicy]
  def deletePolicy(policy: AccessPolicy): IO[Unit]
  def loadPolicy(resourceAndPolicyName: ResourceAndPolicyName): Future[Option[AccessPolicy]]
  def overwritePolicyMembers(id: ResourceAndPolicyName, memberList: Set[WorkbenchSubject]): Future[Unit]
  def overwritePolicy(newPolicy: AccessPolicy): Future[AccessPolicy]
  def listResourceWithAuthdomains(resourceTypeName: ResourceTypeName, resourceId: Set[ResourceId]): IO[Set[Resource]]
  def listAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId): IO[Set[ResourceIdAndPolicyName]]
  def listAccessPolicies(resource: Resource): IO[Set[AccessPolicy]]
  def listAccessPoliciesForUser(resource: Resource, user: WorkbenchUserId): Future[Set[AccessPolicy]]
  def listFlattenedPolicyMembers(resourceAndPolicyName: ResourceAndPolicyName): Future[Set[WorkbenchUserId]]
}
