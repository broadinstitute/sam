package org.broadinstitute.dsde.workbench.sam.openam
import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model._
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
class MockAccessPolicyDAO(private val policies: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = new TrieMap()) extends AccessPolicyDAO {

  override def createResourceType(resourceTypeName: ResourceTypeName): Future[ResourceTypeName] = Future {
    resourceTypeName
  }

  override def createResource(resource: Resource): Future[Resource] = Future {
    if (policies.exists {
      case (ResourceAndPolicyName(`resource`, _), _) => true
      case _ => false
    }) throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, "A resource of this type and name already exists"))
    resource
  }

  override def deleteResource(resource: Resource): Future[Unit] = Future {
    val toRemove = policies.collect {
      case (riapn@ResourceAndPolicyName(`resource`, _), policy: AccessPolicy) => riapn
    }.toSet

    policies -- toRemove
  }

  override def createPolicy(policy: AccessPolicy): Future[AccessPolicy] = Future {
    policies += policy.id -> policy
    policy
  }

  override def deletePolicy(policy: AccessPolicy): Future[Unit] = Future {
    policies -= policy.id
  }

  override def listAccessPolicies(resourceTypeName: ResourceTypeName, user: WorkbenchUserId): Future[Set[ResourceIdAndPolicyName]] = Future {
    policies.collect {
      case (riapn@ResourceAndPolicyName(Resource(`resourceTypeName`, _), _), accessPolicy) if accessPolicy.members.contains(user) => ResourceIdAndPolicyName(riapn.resource.resourceId, riapn.accessPolicyName)
    }.toSet
  }

  override def listAccessPoliciesWithEmail(resourceTypeName: ResourceTypeName, user: WorkbenchUserId): Future[Set[ResourceIdAndPolicyNameWithEmail]] = Future {
    policies.collect {
      case (riapn@ResourceAndPolicyName(Resource(`resourceTypeName`, _), _), accessPolicy) if accessPolicy.members.contains(user) => ResourceIdAndPolicyNameWithEmail(riapn.resource.resourceId, riapn.accessPolicyName, accessPolicy.email)
    }.toSet
  }

  override def loadPolicy(resourceAndPolicyName: ResourceAndPolicyName): Future[Option[AccessPolicy]] = {
    listAccessPolicies(resourceAndPolicyName.resource).map { policies =>
      policies.filter(_.id.accessPolicyName == resourceAndPolicyName.accessPolicyName).toSeq match {
        case Seq() => None
        case Seq(policy) => Option(policy)
        case _ => throw new WorkbenchException(s"More than one policy found for ${resourceAndPolicyName.accessPolicyName}")
      }

    }
  }

  override def overwritePolicy(newPolicy: AccessPolicy): Future[AccessPolicy] = createPolicy(newPolicy)

  override def listAccessPolicies(resource: Resource): Future[Set[AccessPolicy]] = Future {
    policies.collect {
      case (riapn@ResourceAndPolicyName(`resource`, _), policy: AccessPolicy) => policy
    }.toSet
  }

  override def listAccessPoliciesForUser(resource: Resource, user: WorkbenchUserId): Future[Set[AccessPolicy]] = Future {
    policies.collect {
      case (riapn@ResourceAndPolicyName(`resource`, _), policy: AccessPolicy) if policy.members.contains(user) => policy
    }.toSet
  }
}
