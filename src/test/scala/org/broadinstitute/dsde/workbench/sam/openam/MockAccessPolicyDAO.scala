package org.broadinstitute.dsde.workbench.sam.openam
import org.broadinstitute.dsde.workbench.sam.model._

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.Future

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by dvoet on 7/17/17.
  */
class MockAccessPolicyDAO extends AccessPolicyDAO {
  private val policies: mutable.Map[Resource, Set[AccessPolicy]] = new TrieMap()

  override def createPolicy(policy: AccessPolicy): Future[AccessPolicy] = Future {
    listAccessPolicies(policy.resource) map { existingPolicies =>
      policies += (policy.resource -> (existingPolicies.toSet + policy))
    }
    policy
  }

  override def deletePolicy(policy: AccessPolicy): Future[Unit] = Future {
    listAccessPolicies(policy.resource) map { existingPolicies =>
      policies += (policy.resource -> (existingPolicies.toSet - policy))
    }
  }

  override def listAccessPolicies(resource: Resource): Future[TraversableOnce[AccessPolicy]] = Future {
    policies.getOrElse(resource, Set.empty)
  }

  override def deleteResource(resource: Resource): Future[Unit] = Future {
    policies -= resource
  }

  override def createResource(resource: Resource): Future[Resource] = Future {
    policies += resource -> Set.empty
    resource
  }

  override def createResourceType(resourceTypeName: ResourceTypeName): Future[ResourceTypeName] = {
    println("hey your method isn't implemented yet!")
    Future.successful(resourceTypeName) //todo
  }
}
