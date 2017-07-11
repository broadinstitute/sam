package org.broadinstitute.dsde.workbench.sam.openam

import org.broadinstitute.dsde.workbench.sam.model._

import scala.concurrent.Future

/**
  * Created by dvoet on 6/26/17.
  */
trait AccessPolicyDAO {
  def createPolicy(policy: AccessPolicy): Future[AccessPolicy]
  def listAccessPolicies(resourceType: ResourceTypeName, resourceName: ResourceName): Future[TraversableOnce[AccessPolicy]]
  def deletePolicy(policyId: AccessPolicyId): Future[Unit]
}
