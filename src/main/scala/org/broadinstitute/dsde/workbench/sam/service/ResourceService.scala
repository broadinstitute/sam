package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

/**
  * Created by mbemis on 5/22/17.
  */
class ResourceService(private val resourceTypes: Map[ResourceTypeName, ResourceType], val accessPolicyDAO: AccessPolicyDAO, val directoryDAO: DirectoryDAO, val cloudExtensions: CloudExtensions, val googleDomain: String)(implicit val executionContext: ExecutionContext) extends LazyLogging {
  def getResourceTypes(): Future[Map[ResourceTypeName, ResourceType]] = {
    Future.successful(resourceTypes)
  }

  def getResourceType(name: ResourceTypeName): Future[Option[ResourceType]] = {
    Future.successful(resourceTypes.get(name))
  }

  def createResourceType(resourceType: ResourceType): Future[ResourceTypeName] = {
    accessPolicyDAO.createResourceType(resourceType.name)
  }

  def createResource(resourceType: ResourceType, resourceId: ResourceId, userInfo: UserInfo): Future[Resource] = {
    accessPolicyDAO.createResource(Resource(resourceType.name, resourceId)) flatMap { resource =>
      val role = resourceType.roles.find(_.roleName == resourceType.ownerRoleName).getOrElse(throw new WorkbenchException(s"owner role ${resourceType.ownerRoleName} does not exist in $resourceType"))

      val roleMembers: Set[WorkbenchSubject] = Set(userInfo.userId)

      val email = toGoogleGroupEmail(AccessPolicyName(role.roleName.value), Resource(resourceType.name, resourceId))

      for {
        _ <- accessPolicyDAO.createPolicy(AccessPolicy(
          ResourceAndPolicyName(resource, AccessPolicyName(role.roleName.value)),
          roleMembers,
          email,
          Set(role.roleName),
          Set.empty
        ))
      } yield Resource(resourceType.name, resourceId)
    }
  }

  def listUserAccessPolicies(resourceType: ResourceType, userInfo: UserInfo): Future[Set[ResourceIdAndPolicyName]] = {
    accessPolicyDAO.listAccessPolicies(resourceType.name, userInfo.userId)
  }

  def deleteResource(resource: Resource, userInfo: UserInfo): Future[Unit] = {
    for {
      policiesToDelete <- accessPolicyDAO.listAccessPolicies(resource)
      _ <- Future.traverse(policiesToDelete) {accessPolicyDAO.deletePolicy}
      _ <- accessPolicyDAO.deleteResource(resource)
    } yield ()
  }

  def hasPermission(resource: Resource, action: ResourceAction, userInfo: UserInfo): Future[Boolean] = {
    listUserResourceActions(resource, userInfo).map { _.contains(action) }
  }

  def listUserResourceActions(resource: Resource, userInfo: UserInfo): Future[Set[ResourceAction]] = {
    def roleActions(resourceTypeOption: Option[ResourceType], resourceRoleName: ResourceRoleName) = {
      val maybeActions = for {
        resourceType <- resourceTypeOption
        role <- resourceType.roles.find(_.roleName == resourceRoleName)
      } yield {
        role.actions
      }
      maybeActions.getOrElse(Set.empty)
    }

    for {
      resourceType <- getResourceType(resource.resourceTypeName)
      policies <- listResourceAccessPoliciesForUser(resource, userInfo)
    } yield {
      policies.flatMap(policy => policy.actions ++ policy.roles.flatMap(roleActions(resourceType, _)))
    }
  }

  def listUserResourceRoles(resource: Resource, userInfo: UserInfo): Future[Set[ResourceRoleName]] = {
    listResourceAccessPoliciesForUser(resource, userInfo).map { matchingPolicies =>
      matchingPolicies.flatMap(_.roles)
    }
  }

  private def listResourceAccessPoliciesForUser(resource: Resource, userInfo: UserInfo): Future[Set[AccessPolicy]] = {
    accessPolicyDAO.listAccessPoliciesForUser(resource, userInfo.userId)
  }

  //Overwrites an existing policy (keyed by resourceType/resourceId/policyName), saves a new one if it doesn't exist yet
  def overwritePolicy(resourceType: ResourceType, policyName: AccessPolicyName, resource: Resource, policyMembership: AccessPolicyMembership, userInfo: UserInfo): Future[AccessPolicy] = {
    if(!policyMembership.actions.subsetOf(resourceType.actions))
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"You have specified an invalid action for resource type ${resourceType.name}. Valid actions are: ${resourceType.actions.mkString(", ")}"))
    if(!policyMembership.roles.subsetOf(resourceType.roles.map(_.roleName)))
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"You have specified an invalid role for resource type ${resourceType.name}. Valid roles are: ${resourceType.roles.map(_.roleName).mkString(", ")}"))

    val subjectsFromEmails = Future.traverse(policyMembership.memberEmails) {
      directoryDAO.loadSubjectFromEmail
    }.map(_.flatten)

    val email = toGoogleGroupEmail(policyName, resource)

    val actionsByRole = resourceType.roles.map(r => r.roleName -> r.actions).toMap
    val impliedActionsFromRoles = policyMembership.roles.flatMap(actionsByRole)

    subjectsFromEmails.flatMap { members =>
      val resourceAndPolicyName = ResourceAndPolicyName(resource, policyName)
      val newPolicy = AccessPolicy(resourceAndPolicyName, members, email, policyMembership.roles, policyMembership.actions ++ impliedActionsFromRoles)

      accessPolicyDAO.loadPolicy(resourceAndPolicyName).flatMap {
        case None => accessPolicyDAO.createPolicy(newPolicy)
        case Some(_) => accessPolicyDAO.overwritePolicy(newPolicy)
      } andThen {
        case Success(policy) => cloudExtensions.onGroupUpdate(Seq(policy.id)) recover {
          case t: Throwable =>
            logger.error(s"error calling cloudExtensions.onGroupUpdate for ${policy.id}", t)
            throw t
        }
      }
    }
  }

  private def loadAccessPolicyWithEmails(policy: AccessPolicy): Future[AccessPolicyResponseEntry] = {
    val users = policy.members.collect { case userId: WorkbenchUserId => userId }
    val groups = policy.members.collect { case groupName: WorkbenchGroupName => groupName }

    for {
      userEmails <- directoryDAO.loadUsers(users)
      groupEmails <- directoryDAO.loadGroups(groups)
    } yield AccessPolicyResponseEntry(policy.id.accessPolicyName, AccessPolicyMembership(userEmails.toSet[WorkbenchUser].map(_.email.value) ++ groupEmails.map(_.email.value), policy.actions, policy.roles))
  }

  def listResourcePolicies(resource: Resource, userInfo: UserInfo): Future[Set[AccessPolicyResponseEntry]] = {
    accessPolicyDAO.listAccessPolicies(resource).flatMap { policies =>
      //could improve this by making a few changes to listAccessPolicies to return emails. todo for later in the PR process!
      Future.sequence(policies.map(loadAccessPolicyWithEmails))
    }
  }

  def toGoogleGroupEmail(policyName: AccessPolicyName, resource: Resource) = WorkbenchGroupEmail(s"policy-${resource.resourceTypeName.value}-${resource.resourceId.value}-$policyName@$googleDomain") //TODO: Make sure this is a good/unique naming convention and keep Google length limits in mind
  def toGoogleGroupName(groupName: WorkbenchGroupName) = WorkbenchGroupEmail(s"GROUP_${groupName.value}@$googleDomain")

  //todo: use this for google group sync
  private def roleGroupName(resourceType: ResourceType, resourceId: ResourceId, role: ResourceRole) = {
    WorkbenchGroupName(s"${resourceType.name}-${resourceId.value}-${role.roleName.value}")
  }
}
