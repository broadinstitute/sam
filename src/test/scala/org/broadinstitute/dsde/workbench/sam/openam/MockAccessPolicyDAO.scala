package org.broadinstitute.dsde.workbench.sam.openam
import akka.http.scaladsl.model.StatusCodes
import cats.data.NonEmptyList
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/**
  * Created by dvoet on 7/17/17.
  */
class MockAccessPolicyDAO(private val policies: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = new TrieMap()) extends AccessPolicyDAO {
  val resources = new TrieMap[FullyQualifiedResourceId, Resource]()

  override def createResourceType(resourceType: ResourceType, samRequestContext: SamRequestContext): IO[ResourceType] = IO.pure(resourceType)

  override def createResource(resource: Resource, samRequestContext: SamRequestContext): IO[Resource] = IO {
    resources += resource.fullyQualifiedId -> resource
    if (policies.exists {
      case (FullyQualifiedPolicyId(FullyQualifiedResourceId(`resource`.resourceTypeName, `resource`.resourceId), _), _) =>
        true
      case _ => false
    }) throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, "A resource of this type and name already exists"))
    resource
  }

  override def deleteResource(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit] = IO {
    val toRemove = policies.collect {
      case (riapn @ FullyQualifiedPolicyId(`resource`, _), policy: AccessPolicy) => riapn
    }.toSet

    resources -= resource
    policies -- toRemove
  }

  override def loadResourceAuthDomain(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[LoadResourceAuthDomainResult] =
    IO(resources.get(resource)).map{
      rs =>
        rs match {
          case None => LoadResourceAuthDomainResult.ResourceNotFound
          case Some(r) => NonEmptyList.fromList(r.authDomain.toList).fold[LoadResourceAuthDomainResult](LoadResourceAuthDomainResult.NotConstrained)(x => LoadResourceAuthDomainResult.Constrained(x))
        }
    }

  override def removeAuthDomainFromResource(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit] = IO {
    val resourceToRemoveOpt = resources.get(resource)
    resourceToRemoveOpt.map( resourceToRemove => resources += resource -> resourceToRemove.copy(authDomain = Set.empty))
  }

  override def listResourcesConstrainedByGroup(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Set[Resource]] = IO.pure(Set.empty)

  override def createPolicy(policy: AccessPolicy, samRequestContext: SamRequestContext): IO[AccessPolicy] = IO {
    policies += policy.id -> policy
    policy
  }

  override def deletePolicy(policy: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Unit] = IO {
    policies -= policy
  }

  override def listAccessPolicies(resourceTypeName: ResourceTypeName, user: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[ResourceIdAndPolicyName]] = IO {
    policies.collect {
      case (riapn @ FullyQualifiedPolicyId(FullyQualifiedResourceId(`resourceTypeName`, _), _), accessPolicy) if accessPolicy.members.contains(user) => ResourceIdAndPolicyName(riapn.resource.resourceId, riapn.accessPolicyName)
    }.toSet
  }

  override def loadPolicy(policyIdentity: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Option[AccessPolicy]] = {
    listAccessPolicies(policyIdentity.resource, samRequestContext).map { policies =>
      policies.filter(_.id.accessPolicyName == policyIdentity.accessPolicyName).toSeq match {
        case Seq() => None
        case Seq(policy) => Option(policy)
        case _ => throw new WorkbenchException(s"More than one policy found for ${policyIdentity.accessPolicyName}")
      }
    }
  }

  override def overwritePolicy(newPolicy: AccessPolicy, samRequestContext: SamRequestContext): IO[AccessPolicy] = createPolicy(newPolicy, samRequestContext)

  override def overwritePolicyMembers(id: FullyQualifiedPolicyId, memberList: Set[WorkbenchSubject], samRequestContext: SamRequestContext): IO[Unit] = {
    loadPolicy(id, samRequestContext) flatMap {
      case None => throw new Exception("not found")
      case Some(policy) => overwritePolicy(policy.copy(members = memberList), samRequestContext).map(_ => ())
    }
  }

  override def listAccessPolicies(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Stream[AccessPolicy]] = IO {
    policies.collect {
      case (FullyQualifiedPolicyId(`resource`, _), policy: AccessPolicy) => policy
    }.toStream
  }

  override def listAccessPoliciesForUser(resource: FullyQualifiedResourceId, user: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[AccessPolicyWithoutMembers]] = IO {
    policies.collect {
      case (FullyQualifiedPolicyId(`resource`, _), policy: AccessPolicy) if policy.members.contains(user) =>
        AccessPolicyWithoutMembers(policy.id, policy.email, policy.roles, policy.actions, policy.public)
    }.toSet
  }

  override def setPolicyIsPublic(resourceAndPolicyName: FullyQualifiedPolicyId, isPublic: Boolean, samRequestContext: SamRequestContext): IO[Unit] = {
    val maybePolicy = policies.find {
      case (`resourceAndPolicyName`, policy: AccessPolicy) => true
      case _ => false
    }

    maybePolicy match {
      case Some((_, policy: AccessPolicy)) =>
        val newPolicy = policy.copy(public = isPublic)
        IO(policies.put(resourceAndPolicyName, newPolicy))
      case _ => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "policy does not exist")))
    }
  }

  override def listPublicAccessPolicies(resourceTypeName: ResourceTypeName, samRequestContext: SamRequestContext): IO[Stream[ResourceIdAndPolicyName]] = {
    IO.pure(
      policies.collect {
        case (_, policy: AccessPolicy) if policy.public => ResourceIdAndPolicyName(policy.id.resource.resourceId, policy.id.accessPolicyName)
      }.toStream
    )
  }

  // current implementation returns only the WorkbenchUserIds in this policy. it does not fully mock the behavior of LdapAccessPolicyDAO.
  // this function previously just returned an empty set and this is sufficient for the test I'm using it in (as of 10/25/18), so good enough for now
  override def listFlattenedPolicyMembers(policyIdentity: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Set[WorkbenchUser]] = IO {
    val members = policies.collect {
      case (`policyIdentity`, policy: AccessPolicy) => policy.members
    }
    members.flatten.collect {
      case u: WorkbenchUserId => WorkbenchUser(u, Some(GoogleSubjectId(u.value)), WorkbenchEmail("dummy"), None)
    }.toSet
  }

  override def listResourcesWithAuthdomains(resourceTypeName: ResourceTypeName, resourceId: Set[ResourceId], samRequestContext: SamRequestContext)
    : IO[Set[Resource]] = IO.pure(Set.empty)

  override def listResourceWithAuthdomains(resourceId: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Option[Resource]] = IO.pure(None)

  override def listPublicAccessPolicies(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Stream[AccessPolicyWithoutMembers]] = {
    IO.pure(
      policies.collect {
        case (_, policy: AccessPolicy) if policy.public =>
          AccessPolicyWithoutMembers(policy.id, policy.email, policy.roles, policy.actions, policy.public)
      }.toStream
    )
  }

  // Each resource has a list of
  val resourceParents = TrieMap[FullyQualifiedResourceId, FullyQualifiedResourceId]()

  override def getResourceParent(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Option[FullyQualifiedResourceId]] = IO.pure(resourceParents.get(resource))

  override def setResourceParent(childResource: FullyQualifiedResourceId, parentResource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit] = IO {
    if (createsCycle(childResource, parentResource)) {
      throw new WorkbenchExceptionWithErrorReport(
        ErrorReport(StatusCodes.BadRequest, "Cannot set parent as this would introduce a cyclical resource hierarchy")
      )
    } else {
      resourceParents += childResource -> parentResource
    }
  }

  private def createsCycle(childResource: FullyQualifiedResourceId, parentResource: FullyQualifiedResourceId): Boolean = {
    resourceParents.get(parentResource) match {
      case Some(grandparentResource) => childResource == grandparentResource || createsCycle(childResource, grandparentResource)
      case None => false
    }
  }

  override def deleteResourceParent(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit] = IO {
    resourceParents -= resource
  }
}
