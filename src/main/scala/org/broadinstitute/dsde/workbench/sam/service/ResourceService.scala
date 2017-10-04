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

  def createResource(resourceType: ResourceType, resourceName: ResourceName, userInfo: UserInfo): Future[Set[AccessPolicy]] = {
    accessPolicyDAO.createResource(Resource(resourceType.name, resourceName)) flatMap { resource =>
      Future.traverse(resourceType.roles) { role =>
        val roleMembers: Set[WorkbenchSubject] = role.roleName match {
          case resourceType.ownerRoleName => Set(userInfo.userId)
          case _ => Set.empty
        }

        val email = s"policy-${resourceType.name.value}-${resourceName.value}-${role.roleName}@dev.test.firecloud.org" //TODO: Make sure this is a good/unique naming convention and keep Google length limits in mind

        for {
          policy <- accessPolicyDAO.createPolicy(AccessPolicy(
            role.roleName.value,
            resource,
            WorkbenchGroup(WorkbenchGroupName(role.roleName.value), roleMembers, WorkbenchGroupEmail(email)),
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
    //      _ <- accessPolicyDAO.deleteResource(resource) //todo: why does it work even if we don't delete this level?
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

  def overwritePolicy(policyName: String, resource: Resource, policyMembership: AccessPolicyMembership, userInfo: UserInfo): Future[AccessPolicy] = {
    requireAction(resource, ResourceAction("altermembers"), userInfo) {
      val subjectsFromEmails = Future.traverse(policyMembership.memberEmails) {
        directoryDAO.loadSubjectFromEmail
      }.map(_.flatten)

      val email = s"policy-${resource.resourceTypeName.value}-${resource.resourceName.value}-${policyName}@dev.test.firecloud.org" //TODO: Make sure this is a good/unique naming convention and keep Google length limits in mind

      subjectsFromEmails.flatMap { members =>
        val newPolicy = AccessPolicy(policyName, resource, WorkbenchGroup(WorkbenchGroupName(policyName), members, WorkbenchGroupEmail(email)), policyMembership.roles, policyMembership.actions)

        accessPolicyDAO.listAccessPolicies(resource).flatMap { policies =>
          if (policies.map(_.name).contains(policyName)) {
            accessPolicyDAO.overwritePolicy(newPolicy)
          } else accessPolicyDAO.createPolicy(newPolicy)
        }
      }
    }
  }

  def listResourcePolicies(resource: Resource, userInfo: UserInfo): Future[Set[AccessPolicyResponseEntry]] = {
    requireAction(resource, ResourceAction("listmembers"), userInfo) {
      accessPolicyDAO.listAccessPolicies(resource).flatMap { policies =>
        //could improve this by making a few changes to listAccessPolicies to return emails. todo for later in the PR process!
        Future.sequence(policies.map { policy =>
          val users = policy.members.members.collect { case userId: WorkbenchUserId => userId }
          val groups = policy.members.members.collect { case groupName: WorkbenchGroupName => groupName }

          for {
            userEmails <- Future.traverse(users) {
              directoryDAO.loadUser
            }.map(_.flatten.map(_.email.value))
            groupEmails <- Future.traverse(groups) {
              directoryDAO.loadGroup
            }.map(_.flatten.map(_.email.value))
          } yield AccessPolicyResponseEntry(policy.name, AccessPolicyMembership(userEmails ++ groupEmails, policy.actions, policy.roles))
        })
      }
    }
  }

  def toGoogleGroupName(groupName: WorkbenchGroupName) = WorkbenchGroupEmail(s"GROUP_${groupName.value}@${googleDomain}")

  //todo: use this for google group sync
  private def roleGroupName(resourceType: ResourceType, resourceName: ResourceName, role: ResourceRole) = {
    WorkbenchGroupName(s"${resourceType.name}-${resourceName.value}-${role.roleName.value}")
  }

  private def requireAction[T](resource: Resource, action: ResourceAction, userInfo: UserInfo)(op: => Future[T]): Future[T] = {
    hasPermission(resource, action, userInfo) flatMap { canAct =>
      if(canAct) op
      else Future.failed(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, s"You may not perform ${action.toString.toUpperCase} on ${resource.resourceTypeName.value}/${resource.resourceName.value}")))
    }
  }
}
