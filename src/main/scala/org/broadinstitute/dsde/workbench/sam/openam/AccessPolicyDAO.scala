package org.broadinstitute.dsde.workbench.sam.openam

import org.broadinstitute.dsde.workbench.model.{WorkbenchSubject, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.model._

import scala.concurrent.Future

/**
  * Created by dvoet on 6/26/17.
  */
trait AccessPolicyDAO {
  def createPolicy(policy: AccessPolicy): Future[AccessPolicy]
  def createResource(resource: Resource): Future[Resource]
  def createResourceType(resourceTypeName: ResourceTypeName): Future[ResourceTypeName]
  def deleteResource(resource: Resource): Future[Unit]
  def listAccessPolicies(resource: Resource): Future[Set[AccessPolicy]]
  def listAccessPoliciesForUser(resource: Resource, user: WorkbenchUserId): Future[Set[AccessPolicy]]
  def deletePolicy(policy: AccessPolicy): Future[Unit]
  def overwritePolicy(newPolicy: AccessPolicy): Future[AccessPolicy]
}
