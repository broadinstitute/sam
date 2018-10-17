package org.broadinstitute.dsde.workbench.sam.openam
import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model._

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/**
  * Created by dvoet on 7/17/17.
  */
class MockAccessPolicyDAO(private val policies: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = new TrieMap()) extends AccessPolicyDAO {
  val resources = new TrieMap[FullyQualifiedResourceId, Resource]()

  override def createResourceType(resourceTypeName: ResourceTypeName): IO[ResourceTypeName] = IO.pure(resourceTypeName)

  override def createResource(resource: Resource): IO[Resource] = IO {
    resources += resource.fullyQualifiedId -> resource
    if (policies.exists {
      case (FullyQualifiedPolicyId(FullyQualifiedResourceId(`resource`.resourceTypeName, `resource`.resourceId), _), _) =>
        true
      case _ => false
    }) throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, "A resource of this type and name already exists"))
    resource
  }

  override def deleteResource(resource: FullyQualifiedResourceId): IO[Unit] = IO {
    val toRemove = policies.collect {
      case (riapn @ FullyQualifiedPolicyId(`resource`, _), policy: AccessPolicy) => riapn
    }.toSet

    resources -= resource
    policies -- toRemove
  }

  override def loadResourceAuthDomain(resource: FullyQualifiedResourceId): IO[Set[WorkbenchGroupName]] = IO.fromEither(resources.get(resource)
    .toRight(new Exception(s"no resource $resource found in mock policy dao $resources")).map(_.authDomain))

  override def listResourcesConstrainedByGroup(groupId: WorkbenchGroupIdentity): IO[Set[Resource]] = IO.pure(Set.empty)

  override def createPolicy(policy: AccessPolicy): IO[AccessPolicy] = IO {
    policies += policy.id -> policy
    policy
  }

  override def deletePolicy(policy: FullyQualifiedPolicyId): IO[Unit] = IO {
    policies -= policy
  }

  override def listAccessPolicies(resourceTypeName: ResourceTypeName, user: WorkbenchUserId): IO[Set[ResourceIdAndPolicyName]] = IO {
    policies.collect {
      case (riapn @ FullyQualifiedPolicyId(FullyQualifiedResourceId(`resourceTypeName`, _), _), accessPolicy) if accessPolicy.members.contains(user) => ResourceIdAndPolicyName(riapn.resource.resourceId, riapn.accessPolicyName)
    }.toSet
  }

  override def loadPolicy(policyIdentity: FullyQualifiedPolicyId): IO[Option[AccessPolicy]] = {
    listAccessPolicies(policyIdentity.resource).map { policies =>
      policies.filter(_.id.accessPolicyName == policyIdentity.accessPolicyName).toSeq match {
        case Seq() => None
        case Seq(policy) => Option(policy)
        case _ => throw new WorkbenchException(s"More than one policy found for ${policyIdentity.accessPolicyName}")
      }
    }
  }

  override def overwritePolicy(newPolicy: AccessPolicy): IO[AccessPolicy] = createPolicy(newPolicy)

  override def overwritePolicyMembers(id: FullyQualifiedPolicyId, memberList: Set[WorkbenchSubject]): IO[Unit] = {
    loadPolicy(id) flatMap {
      case None => throw new Exception("not found")
      case Some(policy) => overwritePolicy(policy.copy(members = memberList)).map(_ => ())
    }
  }

  override def listAccessPolicies(resource: FullyQualifiedResourceId): IO[Set[AccessPolicy]] = IO {
    policies.collect {
      case (FullyQualifiedPolicyId(`resource`, _), policy: AccessPolicy) => policy
    }.toSet
  }

  override def listAccessPoliciesForUser(resource: FullyQualifiedResourceId, user: WorkbenchUserId): IO[Set[AccessPolicy]] = IO {
    policies.collect {
      case (FullyQualifiedPolicyId(`resource`, _), policy: AccessPolicy) if policy.members.contains(user) => policy
    }.toSet

  }

  override def setPolicyIsPublic(resourceAndPolicyName: FullyQualifiedPolicyId, isPublic: Boolean): IO[Unit] = {
    val maybePolicy = policies.find {
      case (`resourceAndPolicyName`, policy: AccessPolicy) => true
      case _ => false
    }

    maybePolicy match {
      case Some((_, policy: AccessPolicy)) =>
        val newPolicy = policy.copy(isPublic = Option(isPublic))
        IO(policies.put(resourceAndPolicyName, newPolicy))
      case _ => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "policy does not exist")))
    }
  }

  override def listPublicAccessPolicies(resourceTypeName: ResourceTypeName): IO[Stream[ResourceIdAndPolicyName]] = {
    IO.pure(
      policies.collect {
        case (_, policy: AccessPolicy) if policy.isPublic.contains(true) => ResourceIdAndPolicyName(policy.id.resource.resourceId, policy.id.accessPolicyName)
      }.toStream
    )
  }

  override def listFlattenedPolicyMembers(policyIdentity: FullyQualifiedPolicyId): IO[Set[WorkbenchUserId]] = IO.pure(Set.empty)

  override def listResourceWithAuthdomains(
      resourceTypeName: ResourceTypeName,
      resourceId: Set[ResourceId])
    : IO[Set[Resource]] = IO.pure(Set.empty)

  override def listPublicAccessPolicies(resource: FullyQualifiedResourceId): IO[Stream[AccessPolicy]] = {
    IO.pure(
      policies.collect {
        case (_, policy: AccessPolicy) if policy.isPublic.contains(true) => policy
      }.toStream
    )
  }
}
