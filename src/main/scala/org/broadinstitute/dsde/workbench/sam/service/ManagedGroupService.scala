package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by gpolumbo on 2/21/2018.
  */
//(private val resourceTypes: Map[ResourceTypeName, ResourceType], private val accessPolicyDAO: AccessPolicyDAO, private val directoryDAO: DirectoryDAO, private val cloudExtensions: CloudExtensions, private val emailDomain: String)
class ManagedGroupService(private val resourceService: ResourceService, private val resourceTypes: Map[ResourceTypeName, ResourceType], private val accessPolicyDAO: AccessPolicyDAO, private val directoryDAO: DirectoryDAO, private val emailDomain: String) extends LazyLogging {

  def managedGroupType: ResourceType = resourceTypes.getOrElse(ManagedGroupService.ManagedGroupTypeName, throw new WorkbenchException(s"resource type ${ManagedGroupService.ManagedGroupTypeName.value} not found"))
  def memberRole = managedGroupType.roles.find(_.roleName == ManagedGroupService.MemberRoleName).getOrElse(throw new WorkbenchException(s"${ManagedGroupService.MemberRoleName} role does not exist in $managedGroupType"))

  def createManagedGroup(groupId: ResourceId, userInfo: UserInfo): Future[Resource] = {
    for {
      managedGroup <- resourceService.createResource(managedGroupType, groupId, userInfo)
      _ <- createPolicyForMembers(managedGroup)
      policies <- accessPolicyDAO.listAccessPolicies(managedGroup)
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
    directoryDAO.createGroup(BasicWorkbenchGroup(workbenchGroupName, groupMembers, email))
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
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "Cannot create valid email address with the group name provided" , errors.toSeq))
  }

  private def validateGroupNamePattern(str: String): Option[ErrorReport] = {
    str match {
      case ManagedGroupService.GroupNameRe() => None
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

  def deleteManagedGroup(groupId: ResourceId) = {
    directoryDAO.deleteGroup(WorkbenchGroupName(groupId.value))
    resourceService.deleteResource(Resource(managedGroupType.name, groupId))
  }
}

object ManagedGroupService {
  val MemberRoleName = ResourceRoleName("member")
  val ManagedGroupTypeName = ResourceTypeName("managed-group")
  val GroupNameRe = "^[A-z0-9_-]+$".r
}