package org.broadinstitute.dsde.workbench.sam.openam
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicy, AccessPolicyId, ResourceName, ResourceTypeName}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.Future

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by dvoet on 7/17/17.
  */
class MockAccessPolicyDAO extends AccessPolicyDAO {
  private val policies: mutable.Map[AccessPolicyId, AccessPolicy] = new TrieMap()

  override def createPolicy(policy: AccessPolicy): Future[AccessPolicy] = Future {
    policies += policy.id -> policy
    policy
  }

  override def listAccessPolicies(resourceType: ResourceTypeName, resourceName: ResourceName): Future[TraversableOnce[AccessPolicy]] = Future {
    policies.values.filter { policy => policy.resourceType == resourceType && policy.resource == resourceName }
  }

  override def deletePolicy(policyId: AccessPolicyId): Future[Unit] = Future {
    policies -= policyId
  }
}
