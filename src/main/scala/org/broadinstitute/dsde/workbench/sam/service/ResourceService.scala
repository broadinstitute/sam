package org.broadinstitute.dsde.workbench.sam.service

import java.util.UUID
import javax.naming.directory.{AttributeInUseException, NoSuchAttributeException}

import akka.http.scaladsl.model.StatusCodes
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

/**
  * Created by mbemis on 5/22/17.
  */
class ResourceService(private val resourceTypes: Map[ResourceTypeName, ResourceType], val accessPolicyDAO: AccessPolicyDAO, val directoryDAO: DirectoryDAO, val cloudExtensions: CloudExtensions, val emailDomain: String)(implicit val executionContext: ExecutionContext) extends LazyLogging {
  def getResourceTypes(): Future[Map[ResourceTypeName, ResourceType]] = {
    Future.successful(resourceTypes)
  }

  def getResourceType(name: ResourceTypeName): Future[Option[ResourceType]] = {
    Future.successful(resourceTypes.get(name))
  }

  def createResourceType(resourceType: ResourceType): Future[ResourceTypeName] = {
    accessPolicyDAO.createResourceType(resourceType.name)
  }

  def createResource(resourceType: ResourceType, resourceId: ResourceId, userInfo: UserInfo): Future[Resource] = {
    accessPolicyDAO.createResource(Resource(resourceType.name, resourceId)) flatMap { resource =>
      val role = resourceType.roles.find(_.roleName == resourceType.ownerRoleName).getOrElse(throw new WorkbenchException(s"owner role ${resourceType.ownerRoleName} does not exist in $resourceType"))

      val roleMembers: Set[WorkbenchSubject] = Set(userInfo.userId)

      val email = generateGroupEmail(AccessPolicyName(role.roleName.value), Resource(resourceType.name, resourceId))

      for {
        _ <- accessPolicyDAO.createPolicy(AccessPolicy(
          ResourceAndPolicyName(resource, AccessPolicyName(role.roleName.value)),
          roleMembers,
          email,
          Set(role.roleName),
          Set.empty
        ))
      } yield Resource(resourceType.name, resourceId)
    }
  }

  def listUserAccessPolicies(resourceType: ResourceType, userInfo: UserInfo): Future[Set[ResourceIdAndPolicyName]] = {
    accessPolicyDAO.listAccessPolicies(resourceType.name, userInfo.userId)
  }

  def deleteResource(resource: Resource): Future[Unit] = {
    for {
      policiesToDelete <- accessPolicyDAO.listAccessPolicies(resource)
      _ <- Future.traverse(policiesToDelete) {accessPolicyDAO.deletePolicy}
      _ <- accessPolicyDAO.deleteResource(resource)
    } yield ()
  }

  def hasPermission(resource: Resource, action: ResourceAction, userInfo: UserInfo): Future[Boolean] = {
    listUserResourceActions(resource, userInfo).map { _.contains(action) }
  }

  def listUserResourceActions(resource: Resource, userInfo: UserInfo): Future[Set[ResourceAction]] = {
    def roleActions(resourceTypeOption: Option[ResourceType], resourceRoleName: ResourceRoleName) = {
      val maybeActions = for {
        resourceType <- resourceTypeOption
        role <- resourceType.roles.find(_.roleName == resourceRoleName)
      } yield {
        role.actions
      }
      maybeActions.getOrElse(Set.empty)
    }

    for {
      resourceType <- getResourceType(resource.resourceTypeName)
      policies <- listResourceAccessPoliciesForUser(resource, userInfo)
    } yield {
      policies.flatMap(policy => policy.actions ++ policy.roles.flatMap(roleActions(resourceType, _)))
    }
  }

  def listUserResourceRoles(resource: Resource, userInfo: UserInfo): Future[Set[ResourceRoleName]] = {
    listResourceAccessPoliciesForUser(resource, userInfo).map { matchingPolicies =>
      matchingPolicies.flatMap(_.roles)
    }
  }

  private def listResourceAccessPoliciesForUser(resource: Resource, userInfo: UserInfo): Future[Set[AccessPolicy]] = {
    accessPolicyDAO.listAccessPoliciesForUser(resource, userInfo.userId)
  }

  //Overwrites an existing policy (keyed by resourceType/resourceId/policyName), saves a new one if it doesn't exist yet
  def overwritePolicy(resourceType: ResourceType, policyName: AccessPolicyName, resource: Resource, policyMembership: AccessPolicyMembership): Future[AccessPolicy] = {
    mapEmailsToSubjects(policyMembership.memberEmails).flatMap { members: Map[WorkbenchEmail, Option[WorkbenchSubject]] =>
      validatePolicy(resourceType, policyMembership, members)

      val resourceAndPolicyName = ResourceAndPolicyName(resource, policyName)
      val email = generateGroupEmail(policyName, resource)
      val workbenchSubjects = members.values.flatten.toSet
      val newPolicy = AccessPolicy(resourceAndPolicyName, workbenchSubjects, email, policyMembership.roles, policyMembership.actions)

      accessPolicyDAO.loadPolicy(resourceAndPolicyName).flatMap {
        case None => accessPolicyDAO.createPolicy(newPolicy)
        case Some(accessPolicy) => accessPolicyDAO.overwritePolicy(AccessPolicy(newPolicy.id, newPolicy.members, accessPolicy.email, newPolicy.roles, newPolicy.actions ))
      } andThen {
        case Success(policy) => fireGroupUpdateNotification(policy.id)
      }
    }
  }

  private def mapEmailsToSubjects(workbenchEmails: Set[WorkbenchEmail]): Future[Map[WorkbenchEmail, Option[WorkbenchSubject]]] = {
    val eventualSubjects = workbenchEmails.map { workbenchEmail =>
      directoryDAO.loadSubjectFromEmail(workbenchEmail).map(workbenchEmail -> _)
    }

    Future.sequence(eventualSubjects).map(_.toMap)
  }

  // When validating the policy, we want to collect each entity that was problematic and report that back using ErrorReports
  private def validatePolicy(resourceType: ResourceType, policyMembership: AccessPolicyMembership, members: Map[WorkbenchEmail, Option[WorkbenchSubject]]) = {
    val validationErrors = validateMemberEmails(members) ++ validateActions(resourceType, policyMembership) ++ validateRoles(resourceType, policyMembership)
    if (validationErrors.nonEmpty)
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "You have specified an invalid policy", validationErrors.toSeq))
  }

  // Keys are the member email addresses we want to add to the policy, values are the corresponding result of trying to lookup the
  // subject in the Directory using that email address.  If we failed to find a matching subject, then that's not good
  private def validateMemberEmails(emailsToSubjects: Map[WorkbenchEmail, Option[WorkbenchSubject]]): Option[ErrorReport] = {
    val invalidEmails = for((email, subject) <- emailsToSubjects if subject.isEmpty) yield email
    if (invalidEmails.nonEmpty) {
      val emailCauses = invalidEmails.map { workbenchEmail => ErrorReport(s"Invalid member email: ${workbenchEmail}")}
      Some(ErrorReport(s"You have specified at least one invalid member email", emailCauses.toSeq))
    } else None
  }

  private def validateRoles(resourceType: ResourceType, policyMembership: AccessPolicyMembership): Option[ErrorReport] = {
    val invalidRoles = policyMembership.roles -- resourceType.roles.map(_.roleName)
    if (invalidRoles.nonEmpty) {
      val roleCauses = invalidRoles.map { resourceRoleName => ErrorReport(s"Invalid role: ${resourceRoleName}")}
      Some(ErrorReport(s"You have specified an invalid role for resource type ${resourceType.name}. Valid roles are: ${resourceType.roles.map(_.roleName).mkString(", ")}", roleCauses.toSeq))
    } else None
  }

  private def validateActions(resourceType: ResourceType, policyMembership: AccessPolicyMembership): Option[ErrorReport] = {
    val invalidActions = policyMembership.actions.filter(a => !resourceType.actionPatterns.exists(_.matches(a)))
    if (invalidActions.nonEmpty) {
      val actionCauses = invalidActions.map { resourceAction => ErrorReport(s"Invalid action: ${resourceAction}") }
      Some(ErrorReport(s"You have specified an invalid action for resource type ${resourceType.name}. Valid actions are: ${resourceType.actionPatterns.mkString(", ")}", actionCauses.toSeq))
    } else None
  }

  private def fireGroupUpdateNotification(groupId: WorkbenchGroupIdentity) = {
    cloudExtensions.onGroupUpdate(Seq(groupId)) recover {
      case t: Throwable =>
        logger.error(s"error calling cloudExtensions.onGroupUpdate for $groupId", t)
        throw t
    }
  }

  def addSubjectToPolicy(resourceAndPolicyName: ResourceAndPolicyName, subject: WorkbenchSubject): Future[Unit] = {
    directoryDAO.addGroupMember(resourceAndPolicyName, subject) recover {
      case _: AttributeInUseException => // subject is already there
    } andThen {
      case Success(_) => fireGroupUpdateNotification(resourceAndPolicyName)
    }
  }

  def removeSubjectFromPolicy(resourceAndPolicyName: ResourceAndPolicyName, subject: WorkbenchSubject): Future[Unit] = {
    directoryDAO.removeGroupMember(resourceAndPolicyName, subject) recover {
      case _: NoSuchAttributeException => // subject already gone
    } andThen {
      case Success(_) => fireGroupUpdateNotification(resourceAndPolicyName)
    }
  }

  private def loadAccessPolicyWithEmails(policy: AccessPolicy): Future[AccessPolicyMembership] = {
    val users = policy.members.collect { case userId: WorkbenchUserId => userId }
    val groups = policy.members.collect { case groupName: WorkbenchGroupName => groupName }

    for {
      userEmails <- directoryDAO.loadUsers(users)
      groupEmails <- directoryDAO.loadGroups(groups)
    } yield AccessPolicyMembership(userEmails.toSet[WorkbenchUser].map(_.email) ++ groupEmails.map(_.email), policy.actions, policy.roles)
  }

  def listResourcePolicies(resource: Resource): Future[Set[AccessPolicyResponseEntry]] = {
    accessPolicyDAO.listAccessPolicies(resource).flatMap { policies =>
      Future.sequence(policies.map { policy =>
        loadAccessPolicyWithEmails(policy).map { membership =>
          AccessPolicyResponseEntry(policy.id.accessPolicyName, membership, policy.email)
        }
      })
    }
  }

  def loadResourcePolicy(resourceAndPolicyName: ResourceAndPolicyName): Future[Option[AccessPolicyMembership]] = {
    accessPolicyDAO.loadPolicy(resourceAndPolicyName).flatMap {
      case Some(policy) => loadAccessPolicyWithEmails(policy).map(Option(_))
      case None => Future.successful(None)
    }
  }

  private def generateGroupEmail(policyName: AccessPolicyName, resource: Resource) = WorkbenchEmail(s"policy-${UUID.randomUUID}@$emailDomain")
}
