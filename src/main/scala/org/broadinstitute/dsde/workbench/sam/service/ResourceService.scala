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
        group <- directoryDAO.createGroup(SamGroup(SamGroupName(s"${resourceType.name}-${resourceName.value}-${role.roleName}"), roleMembers))
        policy <- accessPolicyDAO.createPolicy(AccessPolicy(
          AccessPolicyId(UUID.randomUUID().toString),
          role.actions,
          resourceType.name,
          resourceName,
          group.name
        ))
      } yield policy
    }
  }
}
