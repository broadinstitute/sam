package org.broadinstitute.dsde.workbench.sam.openam

import org.broadinstitute.dsde.workbench.sam.model._

import scala.concurrent.Future

/**
  * Created by dvoet on 6/26/17.
  */
trait AccessPolicyDAO {
  def createPolicy(policy: AccessPolicy): Future[AccessPolicy]
  def createResource(resource: Resource): Future[Resource]
  def deleteResource(resource: Resource): Future[Unit]
  def listAccessPolicies(resource: Resource): Future[TraversableOnce[AccessPolicy]]
  def deletePolicy(policy: AccessPolicy): Future[Unit]
}
