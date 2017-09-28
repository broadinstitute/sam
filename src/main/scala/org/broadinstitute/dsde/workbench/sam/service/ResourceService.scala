package org.broadinstitute.dsde.workbench.sam.service

import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 5/22/17.
  */
class ResourceService(val accessPolicyDAO: AccessPolicyDAO, val directoryDAO: DirectoryDAO, val googleDomain: String)(implicit val executionContext: ExecutionContext) extends LazyLogging {

  def hasPermission(resourceTypeName: ResourceTypeName, resourceName: ResourceName, action: ResourceAction, userInfo: UserInfo): Future[Boolean] = {
    listUserResourceActions(resourceTypeName, resourceName, userInfo).map { _.contains(action) }
  }

  def listUserResourceActions(resourceTypeName: ResourceTypeName, resourceName: ResourceName, userInfo: UserInfo): Future[Set[ResourceAction]] = {
    listResourceAccessPoliciesForUser(resourceTypeName, resourceName, userInfo).map { matchingPolicies =>
      matchingPolicies.flatMap(_.actions)
    }
  }

  def listUserResourceRoles(resourceTypeName: ResourceTypeName, resourceName: ResourceName, userInfo: UserInfo): Future[Set[ResourceRoleName]] = {
    listResourceAccessPoliciesForUser(resourceTypeName, resourceName, userInfo).map { matchingPolicies =>
      matchingPolicies.flatMap(_.role)
    }
  }

  private def listResourceAccessPoliciesForUser(resourceTypeName: ResourceTypeName, resourceName: ResourceName, userInfo: UserInfo): Future[Set[AccessPolicy]] = {
    for {
      policies <- accessPolicyDAO.listAccessPolicies(resourceTypeName, resourceName)
      groups <- directoryDAO.listUsersGroups(userInfo.userId)
    } yield {
      policies.filter { policy =>
        policy.subject match {
          case user: WorkbenchUserId => userInfo.userId == user
          case group: WorkbenchGroupName => groups.contains(group)
        }
      }.toSet
    }
  }

  def toGoogleGroupName(groupName: WorkbenchGroupName) = WorkbenchGroupEmail(s"GROUP_${groupName.value}@${googleDomain}")

  def createResource(resourceType: ResourceType, resourceName: ResourceName, userInfo: UserInfo): Future[Set[AccessPolicy]] = {
    accessPolicyDAO.listAccessPolicies(resourceType.name, resourceName) flatMap { policies =>
      if (policies.isEmpty) {
        Future.traverse(resourceType.roles) { role =>
          val roleMembers: Set[WorkbenchSubject] = role.roleName match {
            case resourceType.ownerRoleName => Set(userInfo.userId)
            case _ => Set.empty
          }
          val groupName = roleGroupName(resourceType, resourceName, role)
          for {
            group <- directoryDAO.createGroup(WorkbenchGroup(groupName, roleMembers, toGoogleGroupName(groupName)))
            policy <- accessPolicyDAO.createPolicy(AccessPolicy(
              AccessPolicyId(UUID.randomUUID().toString),
              role.actions,
              resourceType.name,
              resourceName,
              group.name,
              Option(role.roleName)
            ))
          } yield policy
        }
      } else {
        Future.failed(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"${resourceType.name} $resourceName already exists.")))
      }
    }
  }

  private def roleGroupName(resourceType: ResourceType, resourceName: ResourceName, role: ResourceRole) = {
    WorkbenchGroupName(s"${resourceType.name}-${resourceName.value}-${role.roleName.value}")
  }

  /**
    * Removes all policies and role groups for a resource
    *
    * @param resourceType
    * @param resourceName
    * @return the number of policies removed
    */
  def deleteResource(resourceType: ResourceType, resourceName: ResourceName): Future[Int] = {
    accessPolicyDAO.listAccessPolicies(resourceType.name, resourceName).flatMap { policies =>
      Future.traverse(policies) { policy =>
        accessPolicyDAO.deletePolicy(policy.id) flatMap { _ =>
          policy.subject match {
            case group: WorkbenchGroupName if policy.role.isDefined => directoryDAO.deleteGroup(group)
            case _ => Future.successful(())
          }
        }
      }.map(_.size)
    }
  }
}
