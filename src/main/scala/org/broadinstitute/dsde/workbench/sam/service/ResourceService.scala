package org.broadinstitute.dsde.workbench.sam.service

import java.util.UUID

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.sam.directory.JndiDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 5/22/17.
  */
class ResourceService(val accessPolicyDAO: AccessPolicyDAO, val directoryDAO: JndiDirectoryDAO)(implicit val executionContext: ExecutionContext) extends LazyLogging {

  def hasPermission(resourceType: ResourceType, resourceName: ResourceName, action: ResourceAction, userInfo: UserInfo): Future[Boolean] = {
    listUserResourceActions(resourceType, resourceName, userInfo).map { _.contains(action) }
  }

  def listUserResourceActions(resourceType: ResourceType, resourceName: ResourceName, userInfo: UserInfo): Future[Set[ResourceAction]] = {
    for {
      policies <- accessPolicyDAO.listAccessPolicies(resourceType.name, resourceName)
      groups <- directoryDAO.listUsersGroups(userInfo.userId)
    } yield {
      val matchingPolicies = policies.filter { policy =>
        policy.subject match {
          case user: SamUserId => userInfo.userId == user
          case group: SamGroupName => groups.contains(group)
        }
      }

      matchingPolicies.flatMap(_.actions).toSet
    }
  }

  def createResource(resourceType: ResourceType, resourceName: ResourceName, userInfo: UserInfo): Future[Set[AccessPolicy]] = {
    Future.traverse(resourceType.roles) { role =>
      val roleMembers: Set[SamSubject] = role.roleName match {
        case resourceType.ownerRoleName => Set(userInfo.userId)
        case _ => Set.empty
      }
      for {
        group <- directoryDAO.createGroup(SamGroup(roleGroupName(resourceType, resourceName, role), roleMembers))
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
  }

  private def roleGroupName(resourceType: ResourceType, resourceName: ResourceName, role: ResourceRole) = {
    SamGroupName(s"${resourceType.name}-${resourceName.value}-${role.roleName.value}")
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
            case group: SamGroupName if policy.role.isDefined => directoryDAO.deleteGroup(group)
            case _ => Future.successful(())
          }
        }
      }.map(_.size)
    }
  }
}
