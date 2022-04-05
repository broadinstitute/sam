package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService.ManagedGroupPolicyName
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.ExecutionContext.Implicits.{global => globalEc}
import scala.concurrent.Future

/**
  * Created by gpolumbo on 2/21/2018.
  */
class ManagedGroupService(
    private val resourceService: ResourceService,
    private val policyEvaluatorService: PolicyEvaluatorService,
    private val resourceTypes: Map[ResourceTypeName, ResourceType],
    private val accessPolicyDAO: AccessPolicyDAO,
    private val directoryDAO: DirectoryDAO,
    private val cloudExtensions: CloudExtensions,
    private val emailDomain: String)
    extends LazyLogging {

  def managedGroupType: ResourceType =
    resourceTypes.getOrElse(
      ManagedGroupService.managedGroupTypeName,
      throw new WorkbenchException(s"resource type ${ManagedGroupService.managedGroupTypeName.value} not found"))

  def createManagedGroup(groupId: ResourceId, userInfo: UserInfo, accessInstructionsOpt: Option[String] = None, samRequestContext: SamRequestContext): IO[Resource] = {
    def adminRole = managedGroupType.ownerRoleName

    val memberPolicy = ManagedGroupService.memberPolicyName -> AccessPolicyMembership(Set.empty, Set.empty, Set(ManagedGroupService.memberRoleName), None, None)
    val adminPolicy = ManagedGroupService.adminPolicyName -> AccessPolicyMembership(Set(userInfo.userEmail), Set.empty, Set(adminRole), None, None)
    val adminNotificationPolicy = ManagedGroupService.adminNotifierPolicyName -> AccessPolicyMembership(
      Set.empty,
      Set.empty,
      Set(ManagedGroupService.adminNotifierRoleName),
      None,
      None)

    validateGroupName(groupId.value)
    for {
      managedGroup <- resourceService.createResource(managedGroupType, groupId, Map(adminPolicy, memberPolicy, adminNotificationPolicy), Set.empty, None, userInfo.userId, samRequestContext)
      policies <- accessPolicyDAO.listAccessPolicies(managedGroup.fullyQualifiedId, samRequestContext)
      workbenchGroup <- createAggregateGroup(managedGroup, policies.toSet, accessInstructionsOpt, samRequestContext)
      _ <- IO.fromFuture(IO(cloudExtensions.publishGroup(workbenchGroup.id)))
    } yield managedGroup
  }

  private def createAggregateGroup(resource: Resource, componentPolicies: Set[AccessPolicy], accessInstructionsOpt: Option[String], samRequestContext: SamRequestContext): IO[BasicWorkbenchGroup] = {
    val email = WorkbenchEmail(constructEmail(resource.resourceId.value))
    val workbenchGroupName = WorkbenchGroupName(resource.resourceId.value)
    val groupMembers: Set[WorkbenchSubject] = componentPolicies.collect {
      // collect only member and admin policies
      case AccessPolicy(id @ FullyQualifiedPolicyId(_, ManagedGroupService.memberPolicyName | ManagedGroupService.adminPolicyName), _, _, _, _, _, _) => id
    }
    directoryDAO.createGroup(BasicWorkbenchGroup(workbenchGroupName, groupMembers, email), accessInstructionsOpt, samRequestContext = samRequestContext)
  }

  private def constructEmail(groupName: String) =
    s"${groupName}@${emailDomain}"

  private def validateGroupName(groupName: String) = {
    val errors = validateGroupNamePattern(groupName) ++ validateGroupNameLength(groupName)
    if (errors.nonEmpty)
      throw new WorkbenchExceptionWithErrorReport(
        ErrorReport(StatusCodes.BadRequest, s"Cannot create valid email address with the group name: $groupName", errors.toSeq))
  }

  private def validateGroupNamePattern(str: String): Option[ErrorReport] =
    str match {
      case ManagedGroupService.groupNameRe() => None
      case _ =>
        Option(ErrorReport(
          s"You have specified a group name that contains characters that are not permitted in an email address. Group name may only contain alphanumeric characters, underscores, and dashes"))
    }

  private val maxLength = 60
  private def validateGroupNameLength(str: String): Option[ErrorReport] =
    if (str.length > maxLength)
      Option(ErrorReport(s"Group Name '$str' is ${str.length} characters in length.  Group Name must be $maxLength characters or fewer"))
    else
      None

  // Per dvoet, when asking for a group, we will just return the group email
  def loadManagedGroup(groupId: ResourceId, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]] =
    directoryDAO.loadGroup(WorkbenchGroupName(groupId.value), samRequestContext).map(_.map(_.email))

  def deleteManagedGroup(groupId: ResourceId, samRequestContext: SamRequestContext): Future[Unit] =
    for {
      // order is important here, we want to make sure we do all the cloudExtensions calls before we touch ldap
      // so failures there do not leave ldap in a bad state
      // resourceService.deleteResource also does cloudExtensions.onGroupDelete first thing
      _ <- cloudExtensions.onGroupDelete(WorkbenchEmail(constructEmail(groupId.value)))
      managedGroupResourceId = FullyQualifiedResourceId(managedGroupType.name, groupId)
      _ <- resourceService.cloudDeletePolicies(managedGroupResourceId, samRequestContext)
      _ <- directoryDAO.deleteGroup(WorkbenchGroupName(groupId.value), samRequestContext).unsafeToFuture()
      _ <- resourceService.deleteResource(managedGroupResourceId, samRequestContext)
    } yield ()

  def listGroups(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Iterable[ManagedGroupMembershipEntry]] =
    for {
      managedGroupsWithRole <- policyEvaluatorService.listUserManagedGroupsWithRole(userId, samRequestContext)
      emailLookup <- directoryDAO.batchLoadGroupEmail(managedGroupsWithRole.map(_.groupName).toSet, samRequestContext)
    } yield {
      val emailLookupMap = emailLookup.toMap
      // This will silently ignore any group where the email could not be loaded. This can happen when a
      // managed group is in an inconsistent state (partially created/deleted or created incorrectly).
      // It also includes only admin and member policies
      managedGroupsWithRole.flatMap { groupAndRole =>
        emailLookupMap
          .get(groupAndRole.groupName)
          .map(email => ManagedGroupMembershipEntry(ResourceId(groupAndRole.groupName.value), groupAndRole.role, email))
      }
    }

  def listPolicyMemberEmails(resourceId: ResourceId, policyName: ManagedGroupPolicyName, samRequestContext: SamRequestContext): IO[LazyList[WorkbenchEmail]] = {
    val policyIdentity =
      FullyQualifiedPolicyId(FullyQualifiedResourceId(ManagedGroupService.managedGroupTypeName, resourceId), policyName)
    accessPolicyDAO.loadPolicy(policyIdentity, samRequestContext) flatMap {
      case Some(policy) => directoryDAO.loadSubjectEmails(policy.members, samRequestContext)
      case None =>
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Group or policy could not be found: $policyIdentity")))
    }
  }

  def overwritePolicyMemberEmails(resourceId: ResourceId, policyName: ManagedGroupPolicyName, emails: Set[WorkbenchEmail], samRequestContext: SamRequestContext): IO[Unit] = {
    val resourceAndPolicyName =
      FullyQualifiedPolicyId(FullyQualifiedResourceId(ManagedGroupService.managedGroupTypeName, resourceId), policyName)
    resourceService.overwritePolicyMembers(resourceAndPolicyName, emails, samRequestContext)
  }

  def addSubjectToPolicy(resourceId: ResourceId, policyName: ManagedGroupPolicyName, subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] = {
    val resourceAndPolicyName =
      FullyQualifiedPolicyId(FullyQualifiedResourceId(ManagedGroupService.managedGroupTypeName, resourceId), policyName)
    resourceService.addSubjectToPolicy(resourceAndPolicyName, subject, samRequestContext)
  }

  def removeSubjectFromPolicy(resourceId: ResourceId, policyName: ManagedGroupPolicyName, subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] = {
    val resourceAndPolicyName =
      FullyQualifiedPolicyId(FullyQualifiedResourceId(ManagedGroupService.managedGroupTypeName, resourceId), policyName)
    resourceService.removeSubjectFromPolicy(resourceAndPolicyName, subject, samRequestContext)
  }

  def requestAccess(resourceId: ResourceId, requesterUserId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit] = {
    def extractGoogleSubjectId(requesterUser: Option[WorkbenchUser]): IO[WorkbenchUserId] =
      (for { u <- requesterUser; s <- u.googleSubjectId } yield s) match {
        case Some(subjectId) => IO.pure(WorkbenchUserId(subjectId.value))
        // don't know how a user would get this far without getting a subject id
        case None => IO.raiseError(new WorkbenchException(s"unable to find subject id for $requesterUserId"))
      }

    getAccessInstructions(resourceId, samRequestContext).flatMap {
      case Some(accessInstructions) =>
        IO.raiseError(
          new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"Please follow special access instructions: $accessInstructions")))
      case None =>
        // Thurloe is the thing that sends the emails and it knows only about google subject ids, not internal sam user ids
        // so we have to do some conversion here which makes the code look less straight forward
        val resourceAndAdminPolicyName =
          FullyQualifiedPolicyId(FullyQualifiedResourceId(ManagedGroupService.managedGroupTypeName, resourceId), ManagedGroupService.adminPolicyName)

        for {
          requesterUser <- directoryDAO.loadUser(requesterUserId, samRequestContext)
          requesterSubjectId <- extractGoogleSubjectId(requesterUser)
          admins <- accessPolicyDAO.listFlattenedPolicyMembers(resourceAndAdminPolicyName, samRequestContext)
          // ignore any admin that does not have a google subject id (they have not registered yet anyway)
          adminUserIds = admins.flatMap { admin =>
            admin.googleSubjectId.map(id => WorkbenchUserId(id.value))
          }
        } yield {
          val notifications = adminUserIds.map { recipientUserId =>
            Notifications.GroupAccessRequestNotification(recipientUserId, WorkbenchGroupName(resourceId.value).value, adminUserIds, requesterSubjectId)
          }
          cloudExtensions.fireAndForgetNotifications(notifications)
        }
    }
  }

  def getAccessInstructions(groupId: ResourceId, samRequestContext: SamRequestContext): IO[Option[String]] =
    directoryDAO.getManagedGroupAccessInstructions(WorkbenchGroupName(groupId.value), samRequestContext)

  def setAccessInstructions(groupId: ResourceId, accessInstructions: String, samRequestContext: SamRequestContext): IO[Unit] =
    directoryDAO.setManagedGroupAccessInstructions(WorkbenchGroupName(groupId.value), accessInstructions, samRequestContext)
}

object ManagedGroupService {
  val managedGroupTypeName = ResourceTypeName("managed-group")
  val groupNameRe = "^[A-z0-9_-]+$".r
  val useAction = ResourceAction("use")

  private val memberValue = "member"
  private val adminValue = "admin"
  private val adminNotifierValue = "admin-notifier"

  type MangedGroupRoleName = ResourceRoleName with AllowedManagedGroupRoleName
  // In lieu of an Enumeration, this trait is being used to ensure that we can only have these Roles in a Managed Group
  sealed trait AllowedManagedGroupRoleName
  val adminRoleName = new ResourceRoleName(adminValue) with AllowedManagedGroupRoleName
  val memberRoleName = new ResourceRoleName(memberValue) with AllowedManagedGroupRoleName
  val adminNotifierRoleName = new ResourceRoleName(adminNotifierValue) with AllowedManagedGroupRoleName

  type ManagedGroupPolicyName = AccessPolicyName with AllowedManagedGroupPolicyName
  // In lieu of an Enumeration, this trait is being used to ensure that we can only have these policies in a Managed Group
  sealed trait AllowedManagedGroupPolicyName
  val adminPolicyName: ManagedGroupPolicyName = new AccessPolicyName(adminValue) with AllowedManagedGroupPolicyName
  val memberPolicyName: ManagedGroupPolicyName = new AccessPolicyName(memberValue) with AllowedManagedGroupPolicyName
  val adminNotifierPolicyName: ManagedGroupPolicyName = new AccessPolicyName(adminNotifierValue) with AllowedManagedGroupPolicyName
  val userMembershipRoleNames: Set[ResourceRoleName] = Set(adminRoleName, memberRoleName)

  object MangedGroupRoleName {
    def unapply(roleName: ResourceRoleName): Option[MangedGroupRoleName] = {
      roleName.value match {
        case `memberValue` => Option(ManagedGroupService.memberRoleName)
        case `adminValue` => Option(ManagedGroupService.adminRoleName)
        case `adminNotifierValue` => Option(ManagedGroupService.adminNotifierRoleName)
        case _ => None
      }
    }
  }

  def getPolicyName(policyName: String): ManagedGroupPolicyName =
    policyName match {
      case `memberValue` => ManagedGroupService.memberPolicyName
      case `adminValue` => ManagedGroupService.adminPolicyName
      case `adminNotifierValue` => ManagedGroupService.adminNotifierPolicyName
      case _ =>
        throw new WorkbenchExceptionWithErrorReport(
          ErrorReport(StatusCodes.NotFound, s"Policy name for managed groups must be one of: ['$adminValue', '$memberValue']"))
    }

  def getRoleName(roleName: String): MangedGroupRoleName =
    roleName match {
      case `memberValue` => ManagedGroupService.memberRoleName
      case `adminValue` => ManagedGroupService.adminRoleName
      case `adminNotifierValue` => ManagedGroupService.adminNotifierRoleName
      case _ =>
        throw new WorkbenchExceptionWithErrorReport(
          ErrorReport(StatusCodes.NotFound, s"Role name for managed groups must be one of: ['$adminValue', '$memberValue']"))
    }
}
