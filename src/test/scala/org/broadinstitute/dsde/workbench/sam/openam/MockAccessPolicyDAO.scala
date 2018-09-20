package org.broadinstitute.dsde.workbench.sam.openam
import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model._

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Created by dvoet on 7/17/17.
  */
class MockAccessPolicyDAO(private val policies: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = new TrieMap()) extends AccessPolicyDAO {

  override def createResourceType(resourceTypeName: ResourceTypeName): IO[ResourceTypeName] = IO.pure(resourceTypeName)

  override def createResource(resource: Resource): IO[Resource] = IO {
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

  override def loadResourceAuthDomain(resource: Resource): IO[Set[WorkbenchGroupName]] = IO.pure(Set.empty)

  override def createPolicy(policy: AccessPolicy): Future[AccessPolicy] = Future {
    policies += policy.id -> policy
    policy
  }

  override def deletePolicy(policy: AccessPolicy): Future[Unit] = Future {
    policies -= policy.id
  }

  override def listAccessPolicies(resourceTypeName: ResourceTypeName, user: WorkbenchUserId): IO[Set[ResourceIdAndPolicyName]] = IO {
    policies.collect {
      case (riapn@ResourceAndPolicyName(Resource(`resourceTypeName`, _, _), _), accessPolicy) if accessPolicy.members.contains(user) => ResourceIdAndPolicyName(riapn.resource.resourceId, riapn.accessPolicyName)
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
      case (_, policy: AccessPolicy) if policy.members.contains(user) => policy
    }.toSet

  }

  override def listFlattenedPolicyMembers(resourceAndPolicyName: ResourceAndPolicyName): Future[Set[WorkbenchUserId]] = {
    Future.successful(Set.empty)
  }
  override def listResourceWithAuthdomains(
      resourceTypeName: ResourceTypeName,
      resourceId: Set[ResourceId])
    : IO[Set[Resource]] = IO.pure(Set.empty)
}
