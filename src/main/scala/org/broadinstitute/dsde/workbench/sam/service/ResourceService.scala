package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 5/22/17.
  */
class ResourceService(val accessPolicyDAO: AccessPolicyDAO, val directoryDAO: DirectoryDAO, val googleDomain: String)(implicit val executionContext: ExecutionContext) extends LazyLogging {

  def createResourceType(resourceType: ResourceType): Future[ResourceTypeName] = {
    accessPolicyDAO.createResourceType(resourceType.name)
  }

  def createResource(resourceType: ResourceType, resourceId: ResourceId, userInfo: UserInfo): Future[Set[AccessPolicy]] = {
    accessPolicyDAO.createResource(Resource(resourceType.name, resourceId)) flatMap { resource =>
      Future.traverse(resourceType.roles) { role =>
        val roleMembers: Set[WorkbenchSubject] = role.roleName match {
          case resourceType.ownerRoleName => Set(userInfo.userId)
          case _ => Set.empty
        }

        val email = toGoogleGroupEmail(role.roleName.value, Resource(resourceType.name, resourceId))

        for {
          policy <- accessPolicyDAO.createPolicy(AccessPolicy(
            role.roleName.value,
            resource,
            WorkbenchGroup(WorkbenchGroupName(role.roleName.value), roleMembers, email),
            Set(role.roleName),
            role.actions
          ))
        } yield policy
      }
    }
  }

  def deleteResource(resource: Resource): Future[Unit] = {
    for {
      policiesToDelete <- accessPolicyDAO.listAccessPolicies(resource)
      _ <- Future.traverse(policiesToDelete){accessPolicyDAO.deletePolicy}
      _ <- accessPolicyDAO.deleteResource(resource)
    } yield ()
  }

  def hasPermission(resource: Resource, action: ResourceAction, userInfo: UserInfo): Future[Boolean] = {
    listUserResourceActions(resource, userInfo).map { _.contains(action) }
  }

  def listUserResourceActions(resource: Resource, userInfo: UserInfo): Future[Set[ResourceAction]] = {
    listResourceAccessPoliciesForUser(resource, userInfo).map { matchingPolicies =>
      matchingPolicies.flatMap(_.actions)
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
  def overwritePolicy(resourceType: ResourceType, policyName: String, resource: Resource, policyMembership: AccessPolicyMembership, userInfo: UserInfo): Future[AccessPolicy] = {
    requireAction(resource, SamResourceActions.alterPolicies, userInfo) {
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
        val newPolicy = AccessPolicy(policyName, resource, WorkbenchGroup(WorkbenchGroupName(policyName), members, email), policyMembership.roles, policyMembership.actions ++ impliedActionsFromRoles)

        accessPolicyDAO.listAccessPolicies(resource).flatMap { policies =>
          if (policies.map(_.name).contains(policyName)) {
            accessPolicyDAO.overwritePolicy(newPolicy)
          } else accessPolicyDAO.createPolicy(newPolicy)
        }
      }
    }
  }

  private def loadAccessPolicy(policy: AccessPolicy): Future[AccessPolicyResponseEntry] = {
    val users = policy.members.members.collect { case userId: WorkbenchUserId => userId }
    val groups = policy.members.members.collect { case groupName: WorkbenchGroupName => groupName }

    for {
      userEmails <- Future.traverse(users) { directoryDAO.loadUser }.map(_.flatten.map(_.email.value))
      groupEmails <- Future.traverse(groups) { directoryDAO.loadGroup }.map(_.flatten.map(_.email.value))
    } yield AccessPolicyResponseEntry(policy.name, AccessPolicyMembership(userEmails ++ groupEmails, policy.actions, policy.roles))
  }

  def listResourcePolicies(resource: Resource, userInfo: UserInfo): Future[Set[AccessPolicyResponseEntry]] = {
    requireAction(resource, SamResourceActions.readPolicies, userInfo) {
      accessPolicyDAO.listAccessPolicies(resource).flatMap { policies =>
        //could improve this by making a few changes to listAccessPolicies to return emails. todo for later in the PR process!
        Future.sequence(policies.map(loadAccessPolicy))
      }
    }
  }

  def toGoogleGroupEmail(policyName: String, resource: Resource) = WorkbenchGroupEmail(s"policy-${resource.resourceTypeName.value}-${resource.resourceId.value}-$policyName@$googleDomain") //TODO: Make sure this is a good/unique naming convention and keep Google length limits in mind
  def toGoogleGroupName(groupName: WorkbenchGroupName) = WorkbenchGroupEmail(s"GROUP_${groupName.value}@$googleDomain")

  //todo: use this for google group sync
  private def roleGroupName(resourceType: ResourceType, resourceId: ResourceId, role: ResourceRole) = {
    WorkbenchGroupName(s"${resourceType.name}-${resourceId.value}-${role.roleName.value}")
  }

  private def requireAction[T](resource: Resource, action: ResourceAction, userInfo: UserInfo)(op: => Future[T]): Future[T] = {
    listUserResourceActions(resource, userInfo) flatMap { actions =>
      if(actions.contains(action)) op
      else if (actions.isEmpty) Future.failed(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Resource ${resource.resourceTypeName.value}/${resource.resourceId.value} not found")))
      else Future.failed(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, s"You may not perform ${action.toString.toUpperCase} on ${resource.resourceTypeName.value}/${resource.resourceId.value}")))
    }
  }
}
