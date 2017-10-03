package org.broadinstitute.dsde.workbench.sam.service

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 5/22/17.
  */
class ResourceService(val accessPolicyDAO: AccessPolicyDAO, val directoryDAO: DirectoryDAO, val googleDomain: String)(implicit val executionContext: ExecutionContext) extends LazyLogging {

  def hasPermission(resourceTypeName: ResourceTypeName, resourceName: ResourceName, action: ResourceAction, userInfo: UserInfo): Future[Boolean] = {
    listUserResourceActions(resourceTypeName, resourceName, userInfo).map { _.contains(action) }
  }

  def listUserResourceActions(resourceTypeName: ResourceTypeName, resourceName: ResourceName, userInfo: UserInfo): Future[Set[ResourceAction]] = {
    listResourceAccessPoliciesForUser(Resource(resourceTypeName, resourceName), userInfo).map { matchingPolicies =>
      matchingPolicies.flatMap(_.actions)
    }
  }

  def listUserResourceRoles(resourceTypeName: ResourceTypeName, resourceName: ResourceName, userInfo: UserInfo): Future[Set[ResourceRoleName]] = {
    listResourceAccessPoliciesForUser(Resource(resourceTypeName, resourceName), userInfo).map { matchingPolicies =>
      matchingPolicies.flatMap(_.role)
    }
  }

  private def listResourceAccessPoliciesForUser(resource: Resource, userInfo: UserInfo): Future[Set[AccessPolicy]] = {
    accessPolicyDAO.listAccessPoliciesForUser(resource, userInfo.userId)
  }

  def toGoogleGroupName(groupName: WorkbenchGroupName) = WorkbenchGroupEmail(s"GROUP_${groupName.value}@${googleDomain}")

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
            WorkbenchGroup(WorkbenchGroupName(role.roleName.value), roleMembers, WorkbenchGroupEmail(email)), //todo
            Option(role.roleName),
            role.actions
          ))
        } yield policy
      }
    }
  }

  def overwritePolicyMembership(policyName: String, resource: Resource, policyMembership: AccessPolicyMembership) = {
    val subjectsFromEmails = Future.traverse(policyMembership.memberEmails) { directoryDAO.loadSubjectFromEmail }.map(_.flatten)

    val email = s"policy-${resource.resourceTypeName.value}-${resource.resourceName.value}-${policyName}@dev.test.firecloud.org" //TODO: Make sure this is a good/unique naming convention and keep Google length limits in mind

    subjectsFromEmails.map { members =>
      val newPolicy = AccessPolicy(policyName, resource, WorkbenchGroup(WorkbenchGroupName(policyName), members, WorkbenchGroupEmail(email)), policyMembership.roles.headOption, policyMembership.actions)

      for {
        _ <- accessPolicyDAO.createPolicy(newPolicy)
        policy <- accessPolicyDAO.overwritePolicyMembers(newPolicy)
      } yield policy
    }
  }

  //todo: use this for google group sync
  private def roleGroupName(resourceType: ResourceType, resourceName: ResourceName, role: ResourceRole) = {
    WorkbenchGroupName(s"${resourceType.name}-${resourceName.value}-${role.roleName.value}")
  }

  def deleteResource(resource: Resource): Future[Unit] = {
    for {
      policiesToDelete <- accessPolicyDAO.listAccessPolicies(resource)
      _ <- Future.traverse(policiesToDelete){accessPolicyDAO.deletePolicy}
//      _ <- accessPolicyDAO.deleteResource(resource) //todo: why does it work even if we don't delete this level?
    } yield ()
  }
}
