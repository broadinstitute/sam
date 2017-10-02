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
    listResourceAccessPoliciesForUser(Resource(resourceTypeName, resourceName), userInfo).map { matchingPolicies =>
      println(matchingPolicies)
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
        for {
          policy <- accessPolicyDAO.createPolicy(AccessPolicy(
            role.roleName.value,
            resource,
            roleMembers,
            Option(role.roleName),
            role.actions
          ))
        } yield policy
      }
    }
  }

  def overwritePolicyMembership(policyName: String, resource: Resource, policyMembership: AccessPolicyMembershipExternal) = {
    println("creating policy")


    val x = Future.traverse(policyMembership.members) { directoryDAO.loadSubjectFromEmail }.map(_.flatten)

    x.map { members =>
      val newPolicy = AccessPolicy(policyName, resource, members, policyMembership.roles.headOption, policyMembership.actions)

      for {
        _ <- accessPolicyDAO.createPolicy(newPolicy)
        x <- accessPolicyDAO.overwritePolicyMembers(newPolicy)
      } yield x
    }
  }

  private def roleGroupName(resourceType: ResourceType, resourceName: ResourceName, role: ResourceRole) = {
    WorkbenchGroupName(s"${resourceType.name}-${resourceName.value}-${role.roleName.value}")
  }

  def deleteResource(resource: Resource): Future[Unit] = {
    for {
      policiesToDelete <- accessPolicyDAO.listAccessPolicies(resource)
      _ <- Future.traverse(policiesToDelete){accessPolicyDAO.deletePolicy}
//      _ <- accessPolicyDAO.deleteResource(resource) why does it work even if we don't delete this level?
    } yield ()
  }
}
