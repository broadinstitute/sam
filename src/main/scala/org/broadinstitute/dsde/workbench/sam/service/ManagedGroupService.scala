package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService.ManagedGroupPolicyName

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
  * Created by gpolumbo on 2/21/2018.
  */
class ManagedGroupService(private val resourceService: ResourceService, private val resourceTypes: Map[ResourceTypeName, ResourceType], private val accessPolicyDAO: AccessPolicyDAO, private val directoryDAO: DirectoryDAO, private val cloudExtensions: CloudExtensions, private val emailDomain: String) extends LazyLogging {

  def managedGroupType: ResourceType = resourceTypes.getOrElse(ManagedGroupService.managedGroupTypeName, throw new WorkbenchException(s"resource type ${ManagedGroupService.managedGroupTypeName.value} not found"))
  def memberRole: ResourceRole = managedGroupType.roles.find(_.roleName == ManagedGroupService.memberRoleName).getOrElse(throw new WorkbenchException(s"${ManagedGroupService.memberRoleName} role does not exist in $managedGroupType"))

  def createManagedGroup(groupId: ResourceId, userInfo: UserInfo): Future[Resource] = {
    println(s"creating ${groupId}")
    for {
      managedGroup <- resourceService.createResource(managedGroupType, groupId, userInfo)
      _ <- createPolicyForMembers(managedGroup)
      _ <- createPolicyForAdminNotification(managedGroup)
      workbenchGroup <- createAggregateGroup(managedGroup, ManagedGroupService.makeMembershipPolicyNames(managedGroup))
      _ <- cloudExtensions.publishGroup(workbenchGroup.id)
    } yield managedGroup
  }

  private def createPolicyForMembers(managedGroup: Resource): Future[AccessPolicy] = {
    val resourceAndPolicyName = ResourceAndPolicyName(managedGroup, ManagedGroupService.memberPolicyName)

    resourceService.createPolicy(resourceAndPolicyName, members = Set.empty, Set(memberRole.roleName), actions = Set.empty)
  }

  private def createPolicyForAdminNotification(managedGroup: Resource): Future[AccessPolicy] = {
    val resourceAndPolicyName = ResourceAndPolicyName(managedGroup, ManagedGroupService.adminNotifierPolicyName)
    println(resourceAndPolicyName)
    resourceService.createPolicy(resourceAndPolicyName, members = Set.empty, roles = Set.empty, actions = Set(SamResourceActions.notifyAdmins))
  }

  private def createAggregateGroup(resource: Resource, componentPolicies: Set[ResourceAndPolicyName]): Future[BasicWorkbenchGroup] = {
    val email = generateManagedGroupEmail(resource.resourceId)
    val workbenchGroupName = WorkbenchGroupName(resource.resourceId.value)
    directoryDAO.createGroup(BasicWorkbenchGroup(workbenchGroupName, componentPolicies.toSet, email))
  }

  private def generateManagedGroupEmail(resourceId: ResourceId): WorkbenchEmail = {
    val groupName = resourceId.value
    validateGroupName(groupName)
    WorkbenchEmail(constructEmail(groupName))
  }

  private def constructEmail(groupName: String) = {
    s"${groupName}@${emailDomain}"
  }

  private def validateGroupName(groupName: String) = {
    val errors = validateGroupNamePattern(groupName) ++ validateGroupNameLength(constructEmail(groupName))
    if (errors.nonEmpty)
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"Cannot create valid email address with the group name: $groupName" , errors.toSeq))
  }

  private def validateGroupNamePattern(str: String): Option[ErrorReport] = {
    str match {
      case ManagedGroupService.groupNameRe() => None
      case _ => Option(ErrorReport(s"You have specified a group name that contains characters that are not permitted in an email address. Group name may only contain alphanumeric characters, underscores, and dashes"))
    }
  }

  private val maxLength = 64
  private def validateGroupNameLength(str: String): Option[ErrorReport] = {
    if (str.length >= maxLength)
      Option(ErrorReport(s"Email address '$str' is ${str.length} characters in length.  Email address length must be shorter than $maxLength characters"))
    else
      None
  }

  // Per dvoet, when asking for a group, we will just return the group email
  def loadManagedGroup(groupId: ResourceId): Future[Option[WorkbenchEmail]] = {
    directoryDAO.loadGroup(WorkbenchGroupName(groupId.value)).map(_.map(_.email))
  }

  def deleteManagedGroup(groupId: ResourceId): Future[Unit] = {
    for {
      _ <- directoryDAO.deleteGroup(WorkbenchGroupName(groupId.value))
      _ <- cloudExtensions.onGroupDelete(WorkbenchEmail(constructEmail(groupId.value)))
      _ <- resourceService.deleteResource(Resource(managedGroupType.name, groupId))
    } yield ()
  }

  def listGroups(userId: WorkbenchUserId): Future[Set[ManagedGroupMembershipEntry]] = {
    for {
      ripns <- accessPolicyDAO.listAccessPolicies(ManagedGroupService.managedGroupTypeName, userId)
      emailLookup <- directoryDAO.batchLoadGroupEmail(ripns.map(ripn => WorkbenchGroupName(ripn.resourceId.value)))
    } yield {
      val emailLookupMap = emailLookup.toMap
      ripns.map { ripn =>
        ManagedGroupMembershipEntry(ripn.resourceId, ripn.accessPolicyName, emailLookupMap(WorkbenchGroupName(ripn.resourceId.value)))
      }
    }
  }

  def listPolicyMemberEmails(resourceId: ResourceId, policyName: ManagedGroupPolicyName): Future[Set[WorkbenchEmail]] = {
    val resourceAndPolicyName = ResourceAndPolicyName(Resource(ManagedGroupService.managedGroupTypeName, resourceId), policyName)
    accessPolicyDAO.loadPolicy(resourceAndPolicyName) flatMap {
      case Some(policy) => directoryDAO.loadSubjectEmails(policy.members)
      case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Group or policy could not be found: $resourceAndPolicyName"))
    }
  }

  def overwritePolicyMemberEmails(resourceId: ResourceId, policyName: ManagedGroupPolicyName, emails: Set[WorkbenchEmail]): Future[AccessPolicy] = {
    val resourceAndPolicyName = ResourceAndPolicyName(Resource(ManagedGroupService.managedGroupTypeName, resourceId), policyName)
    accessPolicyDAO.loadPolicy(resourceAndPolicyName).flatMap {
      case Some(policy) => {
        val updatedPolicy = AccessPolicyMembership(emails, policy.actions, policy.roles)
        resourceService.overwritePolicy(managedGroupType, resourceAndPolicyName.accessPolicyName, resourceAndPolicyName.resource, updatedPolicy)
      }
      case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Group or policy could not be found: $resourceAndPolicyName"))
    }
  }

  def addSubjectToPolicy(resourceId: ResourceId, policyName: ManagedGroupPolicyName, subject: WorkbenchSubject): Future[Unit] = {
    val resourceAndPolicyName = ResourceAndPolicyName(Resource(ManagedGroupService.managedGroupTypeName, resourceId), policyName)
    resourceService.addSubjectToPolicy(resourceAndPolicyName, subject)
  }

  def removeSubjectFromPolicy(resourceId: ResourceId, policyName: ManagedGroupPolicyName, subject: WorkbenchSubject): Future[Unit] = {
    val resourceAndPolicyName = ResourceAndPolicyName(Resource(ManagedGroupService.managedGroupTypeName, resourceId), policyName)
    resourceService.removeSubjectFromPolicy(resourceAndPolicyName, subject)
  }

  def requestAccess(resourceId: ResourceId, requesterUserId: WorkbenchUserId): Future[Unit] = {
    val resourceAndPolicyName = ResourceAndPolicyName(Resource(ManagedGroupService.managedGroupTypeName, resourceId), ManagedGroupService.adminPolicyName)
    accessPolicyDAO.listFlattenedPolicyMembers(resourceAndPolicyName).map { users =>
      val notifications = users.map { recipientUserId =>
        Notifications.GroupAccessRequestNotification(recipientUserId, WorkbenchGroupName(resourceId.value).value, users, requesterUserId)
      }

      cloudExtensions.fireAndForgetNotifications(notifications)
    }
  }
}

object ManagedGroupService {
  val managedGroupTypeName = ResourceTypeName("managed-group")
  val groupNameRe = "^[A-z0-9_-]+$".r
  private val memberValue = "member"
  private val adminValue = "admin"
  private val adminNotifierValue = "admin-notifier"

  type ManagedGroupPolicyName = AccessPolicyName with AllowedManagedGroupPolicyName
  // In lieu of an Enumeration, this trait is being used to ensure that we can only have these policies in a Managed Group
  sealed trait AllowedManagedGroupPolicyName
  val adminPolicyName: ManagedGroupPolicyName = new AccessPolicyName(adminValue) with AllowedManagedGroupPolicyName
  val memberPolicyName: ManagedGroupPolicyName = new AccessPolicyName(memberValue) with AllowedManagedGroupPolicyName
  val adminNotifierPolicyName: ManagedGroupPolicyName = new AccessPolicyName(adminNotifierValue) with AllowedManagedGroupPolicyName

  def getPolicyName(policyName: String): ManagedGroupPolicyName = {
    policyName match {
      case `memberValue` => ManagedGroupService.memberPolicyName
      case `adminValue` => ManagedGroupService.adminPolicyName
      case `adminNotifierValue` => ManagedGroupService.adminNotifierPolicyName
      case _ => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "Policy name for managed groups must be one of: [\"admins\", \"members\"]"))
    }
  }

  def makeMembershipPolicyNames(r: Resource): Set[ResourceAndPolicyName] =
    Set(adminPolicyName, memberPolicyName).map(ResourceAndPolicyName(r, _))

  type MangedGroupRoleName = ResourceRoleName with AllowedManagedGroupRoleName
  // In lieu of an Enumeration, this trait is being used to ensure that we can only have these Roles in a Managed Group
  sealed trait AllowedManagedGroupRoleName
  val adminRoleName = new ResourceRoleName(adminValue) with AllowedManagedGroupRoleName
  val memberRoleName = new ResourceRoleName(memberValue) with AllowedManagedGroupRoleName
  val adminNotifierRoleName = new ResourceRoleName(adminNotifierValue) with AllowedManagedGroupRoleName

  def getRoleName(roleName: String): MangedGroupRoleName = {
    roleName match {
      case `memberValue` => ManagedGroupService.memberRoleName
      case `adminValue` => ManagedGroupService.adminRoleName
      case `adminNotifierValue` => ManagedGroupService.adminNotifierRoleName
      case _ => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Role name for managed groups must be one of: ['$adminValue', '$memberValue']"))
    }
  }
}