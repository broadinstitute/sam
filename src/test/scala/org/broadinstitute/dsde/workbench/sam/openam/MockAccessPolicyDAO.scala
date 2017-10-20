package org.broadinstitute.dsde.workbench.sam.openam
import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchException, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
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
    if (policies.getOrElse(resource.resourceTypeName, Map.empty[ResourceId, Set[AccessPolicy]]).contains(resource.resourceId)) {
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, "A resource of this type and name already exists"))
    }
    policies += resource.resourceTypeName -> (policies.getOrElse(resource.resourceTypeName, Map.empty) ++ Map(resource.resourceId -> Set.empty[AccessPolicy]))
    resource
  }

  override def deleteResource(resource: Resource): Future[Unit] = Future {
    val newResourcesForType = policies(resource.resourceTypeName) - resource.resourceId

    policies += resource.resourceTypeName -> newResourcesForType
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

  override def listAccessPolicies(resourceTypeName: ResourceTypeName, user: WorkbenchUserId): Future[Set[ResourceIdAndPolicyName]] = {
    // note that this is too hard to implement right - currently it is only used to make sure the api routes are right
    Future.successful(Set.empty)
  }

  override def loadPolicy(policyName: AccessPolicyName, resource: Resource): Future[Option[AccessPolicy]] = {
    listAccessPolicies(resource).map { policies =>
      policies.filter(_.name == policyName).toSeq match {
        case Seq() => None
        case Seq(policy) => Option(policy)
        case _ => throw new WorkbenchException(s"More than one policy found for $policyName")
      }

    }
  }

  override def overwritePolicy(newPolicy: AccessPolicy): Future[AccessPolicy] = createPolicy(newPolicy)

  override def listAccessPolicies(resource: Resource): Future[Set[AccessPolicy]] = Future {
    policies.getOrElse(resource.resourceTypeName, Map.empty).getOrElse(resource.resourceId, Set.empty)
  }

  override def listAccessPoliciesForUser(resource: Resource, user: WorkbenchUserId): Future[Set[AccessPolicy]] = Future {
    policies.getOrElse(resource.resourceTypeName, Map.empty).getOrElse(resource.resourceId, Set.empty).filter(_.members.members.contains(user))
  }
}
