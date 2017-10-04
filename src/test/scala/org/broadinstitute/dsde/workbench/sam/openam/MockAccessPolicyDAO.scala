package org.broadinstitute.dsde.workbench.sam.openam
import org.broadinstitute.dsde.workbench.model.{WorkbenchSubject, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.model._

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by dvoet on 7/17/17.
  */
class MockAccessPolicyDAO extends AccessPolicyDAO {
  private val policies: mutable.Map[ResourceTypeName, Map[ResourceId, Set[AccessPolicy]]] = new TrieMap()

  override def createResourceType(resourceTypeName: ResourceTypeName): Future[ResourceTypeName] = Future {
    policies += resourceTypeName -> Map.empty
    resourceTypeName
  }

  override def createResource(resource: Resource): Future[Resource] = Future {
    policies += resource.resourceTypeName -> Map(resource.resourceId -> Set.empty)
    resource
  }

  override def deleteResource(resource: Resource): Future[Unit] = Future {
    //    policies += resource.resourceTypeName -> Map(policies.get)
  }

  override def createPolicy(policy: AccessPolicy): Future[AccessPolicy] = Future {
    listAccessPolicies(policy.resource) map { existingPolicies =>
      policies += (policy.resource.resourceTypeName -> Map(policy.resource.resourceId -> (existingPolicies + policy)))
    }
    policy
  }

  override def deletePolicy(policy: AccessPolicy): Future[Unit] = Future {
    listAccessPolicies(policy.resource) map { existingPolicies =>
      policies += (policy.resource.resourceTypeName -> Map(policy.resource.resourceId -> (existingPolicies - policy)))
    }
  }

  override def overwritePolicy(newPolicy: AccessPolicy): Future[AccessPolicy] = Future.successful(newPolicy)

  override def listAccessPolicies(resource: Resource): Future[Set[AccessPolicy]] = Future {
    policies.getOrElse(resource.resourceTypeName, Map.empty).getOrElse(resource.resourceId, Set.empty)
  }

  override def listAccessPoliciesForUser(resource: Resource, user: WorkbenchUserId): Future[Set[AccessPolicy]] = Future {
    policies.getOrElse(resource.resourceTypeName, Map.empty).getOrElse(resource.resourceId, Set.empty).filter(_.members.members.contains(user))
  }
}
