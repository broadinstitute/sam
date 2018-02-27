package org.broadinstitute.dsde.workbench.sam.service

import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by gpolumbo on 2/21/2018.
  */
class ManagedGroupService(resourceService: ResourceService, val resourceTypes: Map[ResourceTypeName, ResourceType]) extends LazyLogging {

  def managedGroupType: ResourceType = resourceTypes.getOrElse(ManagedGroupService.ManagedGroupTypeName, throw new WorkbenchException(s"resource type ${ManagedGroupService.ManagedGroupTypeName.value} not found"))
  def memberRole = managedGroupType.roles.find(_.roleName == ManagedGroupService.MemberRoleName).getOrElse(throw new WorkbenchException(s"${ManagedGroupService.MemberRoleName} role does not exist in $managedGroupType"))

  // Important Note, see: org/broadinstitute/dsde/workbench/sam/model/SamModel.scala:90
  def createManagedGroup(groupId: ResourceId, userInfo: UserInfo): Future[Resource] = {
    for {
      managedGroup <- resourceService.createResource(managedGroupType, groupId, userInfo)
      _ <- createPolicyForMembers(managedGroup)
      policies <- resourceService.accessPolicyDAO.listAccessPolicies(managedGroup)
      _ <- createAggregateGroup(managedGroup, policies)
    } yield managedGroup
  }

  private def createPolicyForMembers(managedGroup: Resource): Future[AccessPolicy] = {
    val accessPolicyName = AccessPolicyName(memberRole.roleName.value)
    val resourceAndPolicyName = ResourceAndPolicyName(managedGroup, accessPolicyName)
    resourceService.createPolicy(resourceAndPolicyName, members = Set.empty, Set(memberRole.roleName), actions = Set.empty)
  }

  private def createAggregateGroup(resource: Resource, componentPolicies: Set[AccessPolicy]): Future[BasicWorkbenchGroup] = {
    val email = generateManagedGroupEmail(resource.resourceId)
    val workbenchGroupName = WorkbenchGroupName(resource.resourceId.value)
    val groupMembers: Set[WorkbenchSubject] = componentPolicies.map(_.id)
    resourceService.directoryDAO.createGroup(BasicWorkbenchGroup(workbenchGroupName, groupMembers, email))
  }

  // TODO: should be named with {ResourceId.value}@firecloud.org, needs validations on length, "google validity", and uniqueness
  // Answer: keep it simple - just the group ID @ domain, make sure it's valid characters and all less than 64 chars
  // Read RAWLS org.broadinstitute.dsde.rawls.user.UserService#createManagedGroup on how it validates emails (see also: https://support.google.com/a/answer/33386?hl=en&vid=0-237593324832-1519419282150)
  private def generateManagedGroupEmail(resourceId: ResourceId): WorkbenchEmail = {
    val localPart = resourceId.value
    WorkbenchEmail(s"${localPart}@${resourceService.emailDomain}")
  }

  // Per dvoet, when asking for a group, we will just return the group email
  def loadManagedGroup(groupId: ResourceId): Future[Option[WorkbenchEmail]] = {
    resourceService.directoryDAO.loadGroup(WorkbenchGroupName(groupId.value)).map(_.map(_.email))
  }

  // Run the "create" process in reverse
  // 1. Delete the WorkbenchGroup
  // 2. Delete the resource (which by its implementation also takes care of deleting all associated policies)
  def deleteManagedGroup(groupId: ResourceId) = {
    resourceService.directoryDAO.deleteGroup(WorkbenchGroupName(groupId.value))
    resourceService.deleteResource(Resource(managedGroupType.name, groupId))
  }
}

object ManagedGroupService {
  val MemberRoleName = ResourceRoleName("member")
  val ManagedGroupTypeName = ResourceTypeName("managed-group")
}