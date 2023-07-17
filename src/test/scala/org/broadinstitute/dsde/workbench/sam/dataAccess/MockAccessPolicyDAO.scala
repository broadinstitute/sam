package org.broadinstitute.dsde.workbench.sam.dataAccess

import akka.http.scaladsl.model.StatusCodes
import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/** Created by dvoet on 7/17/17.
  */
class MockAccessPolicyDAO(private val resourceTypes: mutable.Map[ResourceTypeName, ResourceType], private val directoryDAO: MockDirectoryDAO)
    extends AccessPolicyDAO {
  def this(resourceTypes: Map[ResourceTypeName, ResourceType], directoryDAO: MockDirectoryDAO) =
    this(new TrieMap[ResourceTypeName, ResourceType]() ++ resourceTypes, directoryDAO)

  def this(resourceTypes: Iterable[ResourceType], directoryDAO: MockDirectoryDAO) =
    this(new TrieMap[ResourceTypeName, ResourceType] ++ resourceTypes.map(rt => rt.name -> rt).toMap, directoryDAO)

  def this(resourceType: ResourceType, directoryDAO: MockDirectoryDAO) =
    this(Map(resourceType.name -> resourceType), directoryDAO)

  def this(directoryDAO: MockDirectoryDAO) =
    this(Map.empty[ResourceTypeName, ResourceType], directoryDAO)

  val resources = new TrieMap[FullyQualifiedResourceId, Resource]()
  val policies: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = directoryDAO.groups

  override def upsertResourceTypes(resourceTypesToInit: Set[ResourceType], samRequestContext: SamRequestContext): IO[Set[ResourceTypeName]] =
    for {
      existingResourceTypes <- loadResourceTypes(resourceTypesToInit.map(_.name), samRequestContext)
      newResourceTypes = resourceTypesToInit -- existingResourceTypes
      _ <- resourceTypesToInit.toList.traverse(createResourceType(_, samRequestContext))
    } yield newResourceTypes.map(_.name)

  override def loadResourceTypes(resourceTypeNames: Set[ResourceTypeName], samRequestContext: SamRequestContext): IO[Set[ResourceType]] =
    IO.pure(resourceTypes.view.filterKeys(resourceTypeNames.contains).values.toSet)

  override def createResourceType(resourceType: ResourceType, samRequestContext: SamRequestContext): IO[ResourceType] = {
    resourceTypes += resourceType.name -> resourceType
    IO.pure(resourceType)
  }

  override def createResource(resource: Resource, samRequestContext: SamRequestContext): IO[Resource] = IO {
    resources += resource.fullyQualifiedId -> resource
    if (
      policies.exists {
        case (FullyQualifiedPolicyId(FullyQualifiedResourceId(`resource`.resourceTypeName, `resource`.resourceId), _), _) =>
          true
        case _ => false
      }
    ) throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, "A resource of this type and name already exists"))
    resource
  } <* resource.accessPolicies.toList.traverse(createPolicy(_, samRequestContext))

  override def deleteResource(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit] = IO {
    val toRemove = policies
      .collect { case (riapn @ FullyQualifiedPolicyId(`resource`, _), policy: AccessPolicy) =>
        riapn
      }
      .toSet[WorkbenchGroupIdentity]

    resources -= resource
    policies.view.filterKeys(k => !toRemove.contains(k))
  }

  override def loadResourceAuthDomain(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[LoadResourceAuthDomainResult] =
    IO(resources.get(resource)).map { rs =>
      rs match {
        case None => LoadResourceAuthDomainResult.ResourceNotFound
        case Some(r) =>
          NonEmptyList
            .fromList(r.authDomain.toList)
            .fold[LoadResourceAuthDomainResult](LoadResourceAuthDomainResult.NotConstrained)(x => LoadResourceAuthDomainResult.Constrained(x))
      }
    }

  override def removeAuthDomainFromResource(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit] = IO {
    val resourceToRemoveOpt = resources.get(resource)
    resourceToRemoveOpt.map(resourceToRemove => resources += resource -> resourceToRemove.copy(authDomain = Set.empty))
  }

  override def listSyncedAccessPolicyIdsOnResourcesConstrainedByGroup(
      groupId: WorkbenchGroupIdentity,
      samRequestContext: SamRequestContext
  ): IO[Set[FullyQualifiedPolicyId]] = IO {
    val groupName: WorkbenchGroupName = groupId match {
      case basicGroupName: WorkbenchGroupName => basicGroupName
      // In real life, policies have a group that can be used to constrain other resources -- if we need the group name
      // of a policy, we can simply look it up in Postgres. In tests, our policies don't have actual groups backing them
      // which means we can't know the name of a policy's group with absolute certainty. If you want to write a test
      // that uses this method and constrains a resource with a policy, use this naming convention
      case policyId: FullyQualifiedPolicyId => WorkbenchGroupName(policyId.toString)
      case _ => throw new WorkbenchException("Wrong kind of group! I recommend trying a WorkbenchGroupName instead")
    }

    (for {
      constrainedResource <- resources.values if constrainedResource.authDomain.contains(groupName)
      constrainedPolicy <- constrainedResource.accessPolicies
    } yield constrainedPolicy.id).toSet
  }

  override def createPolicy(policy: AccessPolicy, samRequestContext: SamRequestContext): IO[AccessPolicy] = IO {
    policies += policy.id -> policy
    policy
  }

  override def deletePolicy(policy: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Unit] = IO {
    policies -= policy
  }

  override def deleteAllResourcePolicies(resourceId: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit] = IO {
    val deletePolicies = policies.collect {
      case (policyId @ FullyQualifiedPolicyId(toDelete, _), _) if toDelete == resourceId => policyId
    }
    policies.subtractAll(deletePolicies)
  }

  override def listAccessPolicies(
      resourceTypeName: ResourceTypeName,
      user: WorkbenchUserId,
      samRequestContext: SamRequestContext
  ): IO[Set[ResourceIdAndPolicyName]] = IO {
    policies.collect {
      case (riapn @ FullyQualifiedPolicyId(FullyQualifiedResourceId(`resourceTypeName`, _), _), accessPolicy) if accessPolicy.members.contains(user) =>
        ResourceIdAndPolicyName(riapn.resource.resourceId, riapn.accessPolicyName)
    }.toSet
  }

  override def loadPolicy(policyIdentity: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Option[AccessPolicy]] =
    listAccessPolicies(policyIdentity.resource, samRequestContext).map { policies =>
      policies.filter(_.id.accessPolicyName == policyIdentity.accessPolicyName).toSeq match {
        case Seq() => None
        case Seq(policy) => Option(policy)
        case _ => throw new WorkbenchException(s"More than one policy found for ${policyIdentity.accessPolicyName}")
      }
    }

  override def overwritePolicy(newPolicy: AccessPolicy, samRequestContext: SamRequestContext): IO[AccessPolicy] = createPolicy(newPolicy, samRequestContext)

  override def overwritePolicyMembers(id: FullyQualifiedPolicyId, memberList: Set[WorkbenchSubject], samRequestContext: SamRequestContext): IO[Unit] =
    loadPolicy(id, samRequestContext) flatMap {
      case None => throw new Exception("not found")
      case Some(policy) => overwritePolicy(policy.copy(members = memberList), samRequestContext).map(_ => ())
    }

  override def listAccessPolicies(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[LazyList[AccessPolicy]] = IO {
    policies
      .collect { case (FullyQualifiedPolicyId(`resource`, _), policy: AccessPolicy) =>
        policy
      }
      .to(LazyList)
  }

  override def listAccessPoliciesForUser(
      resource: FullyQualifiedResourceId,
      user: WorkbenchUserId,
      samRequestContext: SamRequestContext
  ): IO[Set[AccessPolicyWithoutMembers]] = IO {
    policies.collect {
      case (FullyQualifiedPolicyId(`resource`, _), policy: AccessPolicy) if policy.members.contains(user) =>
        AccessPolicyWithoutMembers(policy.id, policy.email, policy.roles, policy.actions, policy.public)
    }.toSet
  }

  override def setPolicyIsPublic(resourceAndPolicyName: FullyQualifiedPolicyId, isPublic: Boolean, samRequestContext: SamRequestContext): IO[Boolean] = {
    val maybePolicy = policies.find {
      case (`resourceAndPolicyName`, policy: AccessPolicy) => true
      case _ => false
    }

    maybePolicy match {
      case Some((_, policy: AccessPolicy)) =>
        val newPolicy = policy.copy(public = isPublic)
        IO {
          policies.put(resourceAndPolicyName, newPolicy) match {
            case Some(AccessPolicy(_, _, _, _, _, _, public)) => public != isPublic
            case _ => false
          }
        }
      case _ => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "policy does not exist")))
    }
  }

  override def listPublicAccessPolicies(resourceTypeName: ResourceTypeName, samRequestContext: SamRequestContext): IO[LazyList[ResourceIdAndPolicyName]] =
    IO.pure(
      policies
        .collect {
          case (_, policy: AccessPolicy) if policy.public => ResourceIdAndPolicyName(policy.id.resource.resourceId, policy.id.accessPolicyName)
        }
        .to(LazyList)
    )

  override def listFlattenedPolicyMembers(policyIdentity: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Set[SamUser]] = IO {
    val members = policies.collect { case (`policyIdentity`, policy: AccessPolicy) =>
      policy.members
    }
    members.flatten.collect { case u: WorkbenchUserId =>
      SamUser(u, Some(GoogleSubjectId(u.value)), WorkbenchEmail("dummy"), None, false, None)
    }.toSet
  }

  override def listResourcesWithAuthdomains(
      resourceTypeName: ResourceTypeName,
      resourceId: Set[ResourceId],
      samRequestContext: SamRequestContext
  ): IO[Set[Resource]] = IO.pure(Set.empty)

  override def listResourceWithAuthdomains(resourceId: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Option[Resource]] = IO.pure(None)

  override def listPublicAccessPolicies(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[LazyList[AccessPolicyWithoutMembers]] =
    IO.pure(
      policies
        .collect {
          case (_, policy: AccessPolicy) if policy.public =>
            AccessPolicyWithoutMembers(policy.id, policy.email, policy.roles, policy.actions, policy.public)
        }
        .to(LazyList)
    )

  override def getResourceParent(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Option[FullyQualifiedResourceId]] = IO(None)

  override def setResourceParent(
      childResource: FullyQualifiedResourceId,
      parentResource: FullyQualifiedResourceId,
      samRequestContext: SamRequestContext
  ): IO[Unit] = ???

  override def deleteResourceParent(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Boolean] = IO.pure(false)

  override def listResourceChildren(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Set[FullyQualifiedResourceId]] = IO(Set.empty)

  override def listUserResourceActions(
      resourceId: FullyQualifiedResourceId,
      user: WorkbenchUserId,
      samRequestContext: SamRequestContext
  ): IO[Set[ResourceAction]] = IO {
    policies
      .collect {
        case (FullyQualifiedPolicyId(`resourceId`, _), policy: AccessPolicy) if policy.members.contains(user) || policy.public =>
          val roleActions = policy.roles.flatMap { role =>
            resourceTypes(resourceId.resourceTypeName).roles.find(_.roleName == role).map(_.actions).getOrElse(Set.empty)
          }

          roleActions ++ policy.actions
      }
      .flatten
      .toSet
  }

  override def listUserResourceRoles(
      resourceId: FullyQualifiedResourceId,
      user: WorkbenchUserId,
      samRequestContext: SamRequestContext
  ): IO[Set[ResourceRoleName]] = IO {
    policies
      .collect {
        case (FullyQualifiedPolicyId(`resourceId`, _), policy: AccessPolicy) if policy.members.contains(user) || policy.public =>
          policy.roles
      }
      .flatten
      .toSet
  }

  override def listUserResourcesWithRolesAndActions(
      resourceTypeName: ResourceTypeName,
      userId: WorkbenchUserId,
      samRequestContext: SamRequestContext
  ): IO[Iterable[ResourceIdWithRolesAndActions]] = IO {
    val forEachPolicy = policies.collect {
      case (fqPolicyId @ FullyQualifiedPolicyId(FullyQualifiedResourceId(`resourceTypeName`, _), _), accessPolicy: AccessPolicy)
          if accessPolicy.members.contains(userId) || accessPolicy.public =>
        if (accessPolicy.public) {
          ResourceIdWithRolesAndActions(fqPolicyId.resource.resourceId, RolesAndActions.empty, RolesAndActions.empty, RolesAndActions.fromPolicy(accessPolicy))
        } else {
          ResourceIdWithRolesAndActions(fqPolicyId.resource.resourceId, RolesAndActions.fromPolicy(accessPolicy), RolesAndActions.empty, RolesAndActions.empty)
        }
    }

    aggregateByResource(forEachPolicy)
  }

  private def loadDirectMemberUserEmails(policy: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[LazyList[WorkbenchEmail]] = {
    val userIds = policies
      .find(_._1 == policy)
      .toList
      .flatMap { case (_, policyGroup) =>
        policyGroup.members.collect { case userId: WorkbenchUserId => userId }
      }
      .to(LazyList)

    userIds.traverse(directoryDAO.loadUser(_, samRequestContext)).map(_.flatMap(_.map(_.email)))
  }

  private def loadDirectMemberGroupEmails(policy: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[LazyList[WorkbenchEmail]] = {
    val groupNames = policies
      .find(_._1 == policy)
      .toList
      .flatMap { case (_, policyGroup) =>
        policyGroup.members.collect { case groupName: WorkbenchGroupName => groupName }
      }
      .to(LazyList)

    groupNames.traverse(directoryDAO.loadGroup(_, samRequestContext)).map(_.flatMap(_.map(_.email)))
  }

  private def loadDirectMemberPolicyIdentifiers(policy: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[LazyList[PolicyInfoResponseBody]] = {
    val policyIds = policies
      .find(_._1 == policy)
      .toList
      .flatMap { case (_, policyGroup) =>
        policyGroup.members.collect { case policyId: FullyQualifiedPolicyId => policyId }
      }
      .to(LazyList)

    policyIds
      .traverse(loadPolicy(_, samRequestContext))
      .map(_.flatMap(_.map(p => PolicyInfoResponseBody(p.id.accessPolicyName, p.email, p.id.resource.resourceTypeName, p.id.resource.resourceId))))
  }

  override def loadPolicyMembership(policyId: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Option[AccessPolicyMembership]] =
    listAccessPolicyMemberships(policyId.resource, samRequestContext).map { policyMemberships =>
      policyMemberships.find(_.policyName == policyId.accessPolicyName).map(_.membership)
    }

  override def listAccessPolicyMemberships(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[LazyList[AccessPolicyWithMembership]] =
    listAccessPolicies(resource, samRequestContext).flatMap { policies =>
      policies.traverse { policy =>
        for {
          users <- loadDirectMemberUserEmails(policy.id, samRequestContext)
          groups <- loadDirectMemberGroupEmails(policy.id, samRequestContext)
          subPolicies <- loadDirectMemberPolicyIdentifiers(policy.id, samRequestContext)
        } yield AccessPolicyWithMembership(
          policy.id.accessPolicyName,
          AccessPolicyMembership(
            users.toSet ++ groups ++ subPolicies.map(_.policyEmail),
            policy.actions,
            policy.roles,
            Option(policy.descendantPermissions),
            Option(subPolicies.toSet)
          ),
          policy.email
        )
      }
    }
}
