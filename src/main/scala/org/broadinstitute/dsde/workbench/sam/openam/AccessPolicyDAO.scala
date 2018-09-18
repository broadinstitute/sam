package org.broadinstitute.dsde.workbench.sam.openam

import org.broadinstitute.dsde.workbench.model.{WorkbenchGroupName, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.model._

import scala.concurrent.Future

/**
  * Created by dvoet on 6/26/17.
  */
trait AccessPolicyDAO {
  def createResourceType(resourceTypeName: ResourceTypeName): Future[ResourceTypeName]

  def createResource(resource: Resource): Future[Resource]
  def deleteResource(resource: Resource): Future[Unit]
  def loadResourceAuthDomain(resource: Resource): Future[Set[WorkbenchGroupName]]

  def createPolicy(policy: AccessPolicy): Future[AccessPolicy]
  def deletePolicy(policy: AccessPolicy): Future[Unit]
  def loadPolicy(resourceAndPolicyName: ResourceAndPolicyName): Future[Option[AccessPolicy]]
  def overwritePolicy(newPolicy: AccessPolicy): Future[AccessPolicy]
  def listAccessPolicies(resourceTypeName: ResourceTypeName, user: WorkbenchUserId): Future[Set[ResourceIdAndPolicyName]]
  def listAccessPolicies(resource: Resource): Future[Set[AccessPolicy]]
  def listAccessPoliciesForUser(resource: Resource, user: WorkbenchUserId): Future[Set[AccessPolicy]]
  def listFlattenedPolicyMembers(resourceAndPolicyName: ResourceAndPolicyName): Future[Set[WorkbenchUserId]]
}
