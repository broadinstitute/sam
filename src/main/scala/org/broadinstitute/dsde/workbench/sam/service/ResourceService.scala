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
class ResourceService(private val resourceTypes: Map[ResourceTypeName, ResourceType], private val accessPolicyDAO: AccessPolicyDAO, private val directoryDAO: DirectoryDAO, private val cloudExtensions: CloudExtensions, private val emailDomain: String)(implicit val executionContext: ExecutionContext) extends LazyLogging {
  def getResourceTypes(): Future[Map[ResourceTypeName, ResourceType]] = {
    Future.successful(resourceTypes)
  }

  def getResourceType(name: ResourceTypeName): Future[Option[ResourceType]] = {
    Future.successful(resourceTypes.get(name))
  }

  def createResourceType(resourceType: ResourceType): Future[ResourceTypeName] = {
    accessPolicyDAO.createResourceType(resourceType.name)
  }

  /**
    * Create a resource with default policies. The default policies contain 1 policy with the same name as the
    * owner role for the resourceType, has the owner role, membership contains only userInfo
    * @param resourceType
    * @param resourceId
    * @param userInfo
    * @return
    */
  def createResource(resourceType: ResourceType, resourceId: ResourceId, userInfo: UserInfo): Future[Resource] = {
    val ownerRole = resourceType.roles.find(_.roleName == resourceType.ownerRoleName).getOrElse(throw new WorkbenchException(s"owner role ${resourceType.ownerRoleName} does not exist in $resourceType"))
    val defaultPolicies: Map[AccessPolicyName, AccessPolicyMembership] = Map(AccessPolicyName(ownerRole.roleName.value) -> AccessPolicyMembership(Set(userInfo.userEmail), Set.empty, Set(ownerRole.roleName)))
    createResource(resourceType, resourceId, defaultPolicies, userInfo)
  }

  /**
    * Validates the resource first and if any validations fail, an exception is thrown with an error report that
    * describes what failed.  If validations pass, then the Resource should be persisted.
    * @param resourceType
    * @param resourceId
    * @param policiesMap
    * @param userInfo
    * @return Future[Resource]
    */
  def createResource(resourceType: ResourceType, resourceId: ResourceId, policiesMap: Map[AccessPolicyName, AccessPolicyMembership], userInfo: UserInfo): Future[Resource] = {
    makeCreatablePolicies(policiesMap).flatMap { policies =>
      validateCreateResource(resourceType, resourceId, policiesMap, userInfo).flatMap {
        case Some(errorReport) => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "Cannot create resource", errorReport))
        case None => persistResource(resourceType, resourceId, policiesMap)
      }
    }
  }
//  def createResource(resourceType: ResourceType, resourceId: ResourceId, policiesMap: Map[AccessPolicyName, AccessPolicyMembership], userInfo: UserInfo): Future[Resource] = {
//    val policies = makeCreatablePolicies(policiesMap)
//    validateCreateResource(resourceType, resourceId, policiesMap, userInfo).map {
//      case Some(errorReport) => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "Cannot create resource", errorReport))
//      case None => persistResource(resourceType, resourceId, policiesMap)
//    }.flatten
//  }

  private def makeCreatablePolicies(policies: Map[AccessPolicyName, AccessPolicyMembership]): Future[Set[AccessPolicyCreator]] = {
    Future.sequence(policies.map {
      case (accessPolicyName, accessPolicyMembership) => makeCreatablePolicy(accessPolicyName, accessPolicyMembership)
    }.toSet)
  }

  private def makeCreatablePolicy(accessPolicyName: AccessPolicyName, accessPolicyMembership: AccessPolicyMembership): Future[AccessPolicyCreator] = {
    mapEmailsToSubjects(accessPolicyMembership.memberEmails).map { emailsToSubjects =>
      AccessPolicyCreator(accessPolicyName, emailsToSubjects, accessPolicyMembership.roles, accessPolicyMembership.actions)
    }
  }

  /**
    * This method only persists the resource and then overwrites/creates the policies for that resource.
    * This method probably should never be called directly.  If you want to create a Resource, use createResource()
    * which will also perform critical validations
    * @param resourceType
    * @param resourceId
    * @param policies
    * @return Future[Resource]
    */
  private def persistResource(resourceType: ResourceType, resourceId: ResourceId, policies: Map[AccessPolicyName, AccessPolicyMembership]): Future[Resource] = {
    for {
      resource <- accessPolicyDAO.createResource(Resource(resourceType.name, resourceId))
      _ <- Future.traverse(policies) {
        case (accessPolicyName, accessPolicyMembership) => overwritePolicy(resourceType, accessPolicyName, resource, accessPolicyMembership)
      }
    } yield resource
  }

  private def validateCreateResource(resourceType: ResourceType, resourceId: ResourceId, policies: Set[AccessPolicyCreator], userInfo: UserInfo) = {
    // Validate:
    // * Owner policy exists, matches reference.conf, and is not empty
    // * Policy - members are valid, "registered" users; actions exist; roles exist
    // * Auth Domains - if provided, then at least one action is constrainable
    val ownerPolicyErrors = validateOwnerPolicy(resourceType, policies)
    if (ownerPolicyErrors.nonEmpty) {
      Future.successful(ownerPolicyErrors)
    } else {
      val policyErrors = Future.sequence(policies.map(policyMembership => validatePolicy(resourceType, policyMembership)))
      policyErrors.map { errors =>
        if (errors.flatten.nonEmpty) {
          Some(ErrorReport("Invalid policies", errors.flatten.toSeq))
        } else {
          None
        }
      }
    }
  }

  private def validateOwnerPolicy(resourceType: ResourceType, policies: Map[AccessPolicyName, AccessPolicyMembership]): Option[ErrorReport] = {
    val ownerExists = policies.exists { case (_, membership) => membership.roles.contains(resourceType.ownerRoleName) && membership.memberEmails.nonEmpty }

    if (!ownerExists) {
      Some(ErrorReport(s"Cannot create resource without at least 1 policy with ${resourceType.ownerRoleName.value} role and non-empty membership"))
    } else {
      None
    }
  }

  def createPolicy(resourceAndPolicyName: ResourceAndPolicyName, members: Set[WorkbenchSubject], roles: Set[ResourceRoleName], actions: Set[ResourceAction]): Future[AccessPolicy] = {
    createPolicy(resourceAndPolicyName, members, generateGroupEmail(), roles, actions)
  }

  def createPolicy(resourceAndPolicyName: ResourceAndPolicyName, members: Set[WorkbenchSubject], email: WorkbenchEmail, roles: Set[ResourceRoleName], actions: Set[ResourceAction]): Future[AccessPolicy] = {
    accessPolicyDAO.createPolicy(AccessPolicy(resourceAndPolicyName, members, email, roles, actions))
  }

  def listUserAccessPolicies(resourceType: ResourceType, userInfo: UserInfo): Future[Set[ResourceIdAndPolicyName]] = {
    accessPolicyDAO.listAccessPolicies(resourceType.name, userInfo.userId)
  }

  // IF Resource ID reuse is allowed (as defined by the Resource Type), then we can delete the resource
  // ELSE Resource ID reuse is not allowed, and we enforce this by deleting all policies associated with the Resource,
  //      but not the Resource itself, thereby orphaning the Resource so that it cannot be used or accessed anymore and
  //      preventing a new Resource with the same ID from being created
  def deleteResource(resource: Resource): Future[Unit] = {
    for {
      policiesToDelete <- accessPolicyDAO.listAccessPolicies(resource)
      _ <- Future.traverse(policiesToDelete) {accessPolicyDAO.deletePolicy}
      _ <- maybeDeleteResource(resource)
      _ <- Future.traverse(policiesToDelete) { policy => cloudExtensions.onGroupDelete(policy.email) }
    } yield ()
  }

  private def maybeDeleteResource(resource: Resource): Future[Unit] = {
    resourceTypes.get(resource.resourceTypeName) match {
      case Some(resourceType) if resourceType.reuseIds => accessPolicyDAO.deleteResource(resource)
      case _ => Future.successful()
    }
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
    validatePolicy(resourceType, policyMembership).map {
      case Some(errorReport) => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "You have specified an invalid policy", errorReport))
      case None => performOverwritePolicy(resourceType, policyName, resource, policyMembership)
    }.flatten
  }

  private def performOverwritePolicy(resourceType: ResourceType, policyName: AccessPolicyName, resource: Resource, policyMembership: AccessPolicyMembership): Future[AccessPolicy] = {
    val resourceAndPolicyName = ResourceAndPolicyName(resource, policyName)
    // TODO: In order for this to work, the Set[WorkbenchEmail] must be converted to Set[WorkbenchSubject]
    // We should have already validated the policy, so we shouldn't need to look the subjects up again from openDJ
    // WorkbenchGroupName and WorkbenchUserId are ValueObjects that just have the email address of the group or user
    // For the steps we're trying to perform in this method, we could just put the policyMembership.memberEmails
    //   directly into either a WorkbenchGroupName or WorkbenchUserId (it doesn't really matter which)
    // BUT - Will that get us in trouble in any way?
    val workbenchSubjects = policyMembership.memberEmails
    accessPolicyDAO.loadPolicy(resourceAndPolicyName).map {
      case None => createPolicy(resourceAndPolicyName, workbenchSubjects.flatten, generateGroupEmail(), policyMembership.roles, policyMembership.actions)
      case Some(accessPolicy) => accessPolicyDAO.overwritePolicy(AccessPolicy(resourceAndPolicyName, workbenchSubjects.flatten, accessPolicy.email, policyMembership.roles, policyMembership.actions))
    } andThen {
      case Success(policy) => fireGroupUpdateNotification(policy.id)
    }
  }

  //Overwrites an existing policy (keyed by resourceType/resourceId/policyName), saves a new one if it doesn't exist yet
  def overwritePolicyOld(resourceType: ResourceType, policyName: AccessPolicyName, resource: Resource, policyMembership: AccessPolicyMembership): Future[AccessPolicy] = {
    mapEmailsToSubjects(policyMembership.memberEmails).flatMap { members: Map[WorkbenchEmail, Option[WorkbenchSubject]] =>
      validatePolicy(resourceType, policyMembership, members)

      val resourceAndPolicyName = ResourceAndPolicyName(resource, policyName)
      val workbenchSubjects = members.values.flatten.toSet

      accessPolicyDAO.loadPolicy(resourceAndPolicyName).flatMap {
        case None => createPolicy(resourceAndPolicyName, workbenchSubjects, generateGroupEmail(), policyMembership.roles, policyMembership.actions)
        case Some(accessPolicy) => accessPolicyDAO.overwritePolicy(AccessPolicy(resourceAndPolicyName, workbenchSubjects, accessPolicy.email, policyMembership.roles, policyMembership.actions ))
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
  private def validatePolicy(resourceType: ResourceType, policyMembership: AccessPolicyMembership): Future[Option[ErrorReport]] = {
    mapEmailsToSubjects(policyMembership.memberEmails).map { members: Map[WorkbenchEmail, Option[WorkbenchSubject]] =>
    val validationErrors = validateMemberEmails(members) ++ validateActions(resourceType, policyMembership) ++ validateRoles(resourceType, policyMembership)
      if (validationErrors.nonEmpty) {
        Some(ErrorReport("You have specified an invalid policy", validationErrors.toSeq))
      } else None
    }
  }

  // When validating the policy, we want to collect each entity that was problematic and report that back using ErrorReports
//  private def validatePolicy(resourceType: ResourceType, policyMembership: AccessPolicyMembership, members: Map[WorkbenchEmail, Option[WorkbenchSubject]]) = {
//    val validationErrors = validateMemberEmails(members) ++ validateActions(resourceType, policyMembership) ++ validateRoles(resourceType, policyMembership)
//    if (validationErrors.nonEmpty)
//      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "You have specified an invalid policy", validationErrors.toSeq))
//  }

  // Keys are the member email addresses we want to add to the policy, values are the corresponding result of trying to lookup the
  // subject in the Directory using that email address.  If we failed to find a matching subject, then that's not good
  private def validateMemberEmails(emailsToSubjects: Map[WorkbenchEmail, Option[WorkbenchSubject]]): Option[ErrorReport] = {
    val invalidEmails = emailsToSubjects.collect { case (email, None) => email }
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

  private def generateGroupEmail() = WorkbenchEmail(s"policy-${UUID.randomUUID}@$emailDomain")
}
