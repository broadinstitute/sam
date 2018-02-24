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

  // TODO: There's still something weird with these:  could/should be in companion object, should only need to be evaluated once, memoize?
  def managedGroupType: ResourceType = resourceTypes.getOrElse(ManagedGroupService.ManagedGroupTypeName, throw new WorkbenchException(s"resource type ${ManagedGroupService.ManagedGroupTypeName.value} not found"))
  def memberRole = managedGroupType.roles.find(_.roleName == ManagedGroupService.MemberRoleName).getOrElse(throw new WorkbenchException(s"${ManagedGroupService.MemberRoleName} role does not exist in $managedGroupType"))

  // Important Note, see: org/broadinstitute/dsde/workbench/sam/model/SamModel.scala:90
  // 1. Create new Resource with ResourceType: "managed-group"
  //    - This will also create the Admin Policy for this Resource
  //      - Admin Policy will grant "admin" role to the user specified by {user_info} on the newly created Resource
  // 2. Create a new Policy (which is a SAM Group) for granting users the "member" role on the newly created Resource
  //    - This new policy will be created with an empty set of Subjects
  // 3. Create a new SAM Group for "All Managed Group Members"
  //    - The set of Subjects in this group will be the Admin Policy (created in step 1) and the Members Policy (created in step 2)
  def createManagedGroup(groupId: ResourceId, userInfo: UserInfo): Future[Resource] = {
    // TODO: Do the policy emails need to be "nice" google group emails???
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
    resourceService.directoryDAO.createGroup(BasicWorkbenchGroup(workbenchGroupName, Set.empty, email))
  }

  // TODO: should be named with {ResourceId.value}@firecloud.org, needs validations on length, "google validity", and uniqueness
  // TODO: Ask Doug - should the name be prefixed with GROUP_ as it is today in RAWLS?
  // TODO: Read RAWLS org.broadinstitute.dsde.rawls.user.UserService#createManagedGroup on how it validates emails (see also: https://support.google.com/a/answer/33386?hl=en&vid=0-237593324832-1519419282150)
  private def generateManagedGroupEmail(resourceId: ResourceId): WorkbenchEmail = {
    val localPart = resourceId.value
    WorkbenchEmail(s"${localPart}@${resourceService.emailDomain}")
  }

  def deleteManagedGroup(groupId: ResourceId) = resourceService.deleteResource(Resource(managedGroupType.name, groupId))
}

object ManagedGroupService {
  // TODO: Consider moving these to config/package.scala which seems to be where we're defining how to extract data from reference.conf
  val MemberRoleName = ResourceRoleName("member")
  val ManagedGroupTypeName = ResourceTypeName("managed-group")
}