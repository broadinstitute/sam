package org.broadinstitute.dsde.workbench.sam.service

import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.implicits._
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
  implicit val cs = IO.contextShift(executionContext) //for running IOs in paralell

  private case class ValidatableAccessPolicy(policyName: AccessPolicyName, emailsToSubjects: Map[WorkbenchEmail, Option[WorkbenchSubject]], roles: Set[ResourceRoleName], actions: Set[ResourceAction])

  def getResourceTypes(): Future[Map[ResourceTypeName, ResourceType]] = {
    Future.successful(resourceTypes)
  }

  def getResourceType(name: ResourceTypeName): Future[Option[ResourceType]] = {
    Future.successful(resourceTypes.get(name))
  }

  def createResourceType(resourceType: ResourceType): IO[ResourceTypeName] = {
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
    createResource(resourceType, resourceId, defaultPolicies, Set.empty, userInfo)
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
  def createResource(resourceType: ResourceType, resourceId: ResourceId, policiesMap: Map[AccessPolicyName, AccessPolicyMembership], authDomain: Set[WorkbenchGroupName], userInfo: UserInfo): Future[Resource] = {
    makeValidatablePolicies(policiesMap).flatMap { policies =>
      validateCreateResource(resourceType, resourceId, policies, authDomain, userInfo).flatMap {
        case Seq() => persistResource(resourceType, resourceId, policies, authDomain)
        case errorReports: Seq[ErrorReport] => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "Cannot create resource", errorReports))
      }
    }
  }

  /**
    * This method only persists the resource and then overwrites/creates the policies for that resource.
    * Be very careful if calling this method directly because it will not validate the resource or its policies.
    * If you want to create a Resource, use createResource() which will also perform critical validations
    * @param resourceType
    * @param resourceId
    * @param policies
    * @param authDomain
    * @return Future[Resource]
    */
  private def persistResource(resourceType: ResourceType, resourceId: ResourceId, policies: Set[ValidatableAccessPolicy], authDomain: Set[WorkbenchGroupName]) = {
    for {
      resource <- accessPolicyDAO.createResource(Resource(resourceType.name, resourceId, authDomain)).unsafeToFuture()
      _ <- Future.traverse(policies)(createOrUpdatePolicy(resource, _))
    } yield resource
  }

  private def validateCreateResource(resourceType: ResourceType, resourceId: ResourceId, policies: Set[ValidatableAccessPolicy], authDomain: Set[WorkbenchGroupName], userInfo: UserInfo) = {
    for {
      ownerPolicyErrors <- Future.successful(validateOwnerPolicyExists(resourceType, policies))
      policyErrors <- Future.successful(policies.flatMap(policy => validatePolicy(resourceType, policy)))
      authDomainErrors <- validateAuthDomain(resourceType, authDomain, userInfo)
    } yield (ownerPolicyErrors ++ policyErrors ++ authDomainErrors).toSeq
  }

  private def validateOwnerPolicyExists(resourceType: ResourceType, policies: Set[ValidatableAccessPolicy]): Option[ErrorReport] = {
    policies.exists { policy => policy.roles.contains(resourceType.ownerRoleName) && policy.emailsToSubjects.nonEmpty } match {
      case true => None
      case false => Option(ErrorReport(s"Cannot create resource without at least 1 policy with ${resourceType.ownerRoleName.value} role and non-empty membership"))
    }
  }

  private def validateAuthDomain(resourceType: ResourceType, authDomain: Set[WorkbenchGroupName], userInfo: UserInfo): Future[Option[ErrorReport]] = {
    validateAuthDomainPermissions(authDomain, userInfo).map { permissionsErrors =>
      val constrainableErrors = validateAuthDomainConstraints(resourceType, authDomain).toSeq
      val errors = constrainableErrors ++ permissionsErrors.flatten
      if (errors.nonEmpty) {
        Option(ErrorReport("Invalid Auth Domain specified", errors))
      } else None
    }
  }

  private def validateAuthDomainPermissions(authDomain: Set[WorkbenchGroupName], userInfo: UserInfo): Future[Set[Option[ErrorReport]]] = {
    Future.traverse(authDomain) { groupName =>
      val resource = Resource(ManagedGroupService.managedGroupTypeName, ResourceId(groupName.value))
      hasPermission(resource, ManagedGroupService.useAction, userInfo).map {
        case false => Option(ErrorReport(s"You do not have access to $groupName or $groupName does not exist"))
        case _ => None
      }
    }
  }

  private def validateAuthDomainConstraints(resourceType: ResourceType, authDomain: Set[WorkbenchGroupName]): Option[ErrorReport] = {
    if (authDomain.nonEmpty && !resourceType.isAuthDomainConstrainable) {
      Option(ErrorReport(s"Auth Domain is not permitted on resource of type: ${resourceType.name}"))
    } else None
  }

  def loadResourceAuthDomain(resource: Resource): Future[Set[WorkbenchGroupName]] = {
    accessPolicyDAO.loadResourceAuthDomain(resource).unsafeToFuture()
  }

  def createPolicy(resourceAndPolicyName: ResourceAndPolicyName, members: Set[WorkbenchSubject], roles: Set[ResourceRoleName], actions: Set[ResourceAction]): Future[AccessPolicy] = {
    createPolicy(resourceAndPolicyName, members, generateGroupEmail(), roles, actions)
  }

  def createPolicy(resourceAndPolicyName: ResourceAndPolicyName, members: Set[WorkbenchSubject], email: WorkbenchEmail, roles: Set[ResourceRoleName], actions: Set[ResourceAction]): Future[AccessPolicy] = {
    accessPolicyDAO.createPolicy(AccessPolicy(resourceAndPolicyName, members, email, roles, actions)).unsafeToFuture()
  }

  def listUserAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId): IO[Set[UserPolicyResponse]] = for{
      rt <- IO.fromEither(resourceTypes.get(resourceTypeName).toRight(new WorkbenchException(s"missing configration for resourceType ${resourceTypeName}")))
      isConstrained = rt.isAuthDomainConstrainable
      ridAndPolicyName <- accessPolicyDAO.listAccessPolicies(resourceTypeName, userId) // List all policies of a given resourceType the user is a member of
      rids = ridAndPolicyName.map(_.resourceId)


      resources <- if(isConstrained) accessPolicyDAO.listResourceWithAuthdomains(resourceTypeName, rids) else IO.pure(Set.empty)
      authDomainMap = resources.map(x => x.resourceId -> x.authDomain).toMap

      allAuthDomainResourcesUserIsMemberOf <- if(isConstrained) accessPolicyDAO.listAccessPolicies(ManagedGroupService.managedGroupTypeName, userId) else IO.pure(Set.empty[ResourceIdAndPolicyName])

      results = ridAndPolicyName.map{
        rnp =>
          if(isConstrained){
            authDomainMap.get(rnp.resourceId) match{
              case Some(authDomains) =>
                val userNotMemberOf = authDomains.filterNot(x => allAuthDomainResourcesUserIsMemberOf.map(_.resourceId).contains(ResourceId(x.value)))
                Some(UserPolicyResponse(rnp.resourceId, rnp.accessPolicyName, authDomains, userNotMemberOf))
              case None =>
                logger.error(s"ldap has corrupted data. ${rnp.resourceId} should have auth domains defined")
                none[UserPolicyResponse]
            }
          } else UserPolicyResponse(rnp.resourceId, rnp.accessPolicyName, Set.empty, Set.empty).some
      }
    } yield results.flatten

  // IF Resource ID reuse is allowed (as defined by the Resource Type), then we can delete the resource
  // ELSE Resource ID reuse is not allowed, and we enforce this by deleting all policies associated with the Resource,
  //      but not the Resource itself, thereby orphaning the Resource so that it cannot be used or accessed anymore and
  //      preventing a new Resource with the same ID from being created
  def deleteResource(resource: Resource): Future[Unit] = {
    for {
      policiesToDelete <- accessPolicyDAO.listAccessPolicies(resource).unsafeToFuture()
      // remove from cloud extensions first so a failure there does not leave ldap in a bad state
      _ <- Future.traverse(policiesToDelete) { policy => cloudExtensions.onGroupDelete(policy.email) }
      _ <- policiesToDelete.toList.parTraverse(accessPolicyDAO.deletePolicy).unsafeToFuture()
      _ <- maybeDeleteResource(resource)
    } yield ()
  }

  private def maybeDeleteResource(resource: Resource): Future[Unit] = {
    resourceTypes.get(resource.resourceTypeName) match {
      case Some(resourceType) if resourceType.reuseIds => accessPolicyDAO.deleteResource(resource).unsafeToFuture()
      case _ => Future.successful(())
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

  /**
    * Overwrites an existing policy (keyed by resourceType/resourceId/policyName), saves a new one if it doesn't exist yet
    * @param resourceType
    * @param policyName
    * @param resource
    * @param policyMembership
    * @return
    */
  def overwritePolicy(resourceType: ResourceType, policyName: AccessPolicyName, resource: Resource, policyMembership: AccessPolicyMembership): Future[AccessPolicy] = {
    makeCreatablePolicy(policyName, policyMembership).flatMap { policy =>
      validatePolicy(resourceType, policy) match {
        case Some(errorReport) => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "You have specified an invalid policy", errorReport))
        case None => createOrUpdatePolicy(resource, policy)
      }
    }
  }

  /**
    * Overwrites an existing policy's membership (keyed by resourceType/resourceId/policyName) if it exists
    * @param resourceType
    * @param policyName
    * @param resource
    * @param membersList
    * @return
    */
  def overwritePolicyMembers(resourceType: ResourceType, policyName: AccessPolicyName, resource: Resource, membersList: Set[WorkbenchEmail]): Future[AccessPolicy] = {
    loadResourcePolicy(ResourceAndPolicyName(resource, policyName)).flatMap {
      case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "Policy not found"))
      case Some(accessPolicyMembership) => overwritePolicy(resourceType, policyName, resource, accessPolicyMembership.copy(memberEmails = membersList))
    }
  }

  /**
    * Overwrites the policy if it already exists or creates a new policy entry if it does not exist.
    * Triggers update to Google Group upon successfully updating the policy.
    * Note:  This method DOES NOT validate the policy and should probably not be called directly unless you know the
    * contents are valid.  To validate and save the policy, use overwritePolicy()
    * @param resource
    * @param policy
    * @return
    */
  private def createOrUpdatePolicy(resource: Resource, policy: ValidatableAccessPolicy): Future[AccessPolicy] = {
    val resourceAndPolicyName = ResourceAndPolicyName(resource, policy.policyName)
    val workbenchSubjects = policy.emailsToSubjects.values.flatten.toSet

    accessPolicyDAO.loadPolicy(resourceAndPolicyName).flatMap {
      case None => createPolicy(resourceAndPolicyName, workbenchSubjects, generateGroupEmail(), policy.roles, policy.actions)
      case Some(accessPolicy) => accessPolicyDAO.overwritePolicy(AccessPolicy(resourceAndPolicyName, workbenchSubjects, accessPolicy.email, policy.roles, policy.actions))
    } andThen {
      case Success(policy) =>
        fireGroupUpdateNotification(policy.id)
    }
  }

  private def mapEmailsToSubjects(workbenchEmails: Set[WorkbenchEmail]): Future[Map[WorkbenchEmail, Option[WorkbenchSubject]]] = {
    val eventualSubjects = workbenchEmails.map { workbenchEmail =>
      directoryDAO.loadSubjectFromEmail(workbenchEmail).map(workbenchEmail -> _)
    }

    Future.sequence(eventualSubjects).map(_.toMap)
  }

  /**
    * Validates a policy in the context of a ResourceType.  When validating the policy, we want to collect each entity
    * that was problematic and report that back using ErrorReports
    * @param resourceType
    * @param policy
    * @return
    */
  private def validatePolicy(resourceType: ResourceType, policy: ValidatableAccessPolicy): Option[ErrorReport] = {
    val validationErrors = validateMemberEmails(policy.emailsToSubjects) ++ validateActions(resourceType, policy) ++ validateRoles(resourceType, policy)
    if (validationErrors.nonEmpty) {
      Some(ErrorReport("You have specified an invalid policy", validationErrors.toSeq))
    } else None
  }

  /**
    * A valid email is one that matches the email address for a previously persisted WorkbenchSubject.
    * @param emailsToSubjects Keys are the member email addresses we want to add to the policy, values are the
    *                         corresponding result of trying to lookup the subject in the Directory using that email
    *                         address.  If we failed to find a matching subject, then the email address is invalid
    * @return an optional ErrorReport enumerating all invalid email addresses
    */
  private def validateMemberEmails(emailsToSubjects: Map[WorkbenchEmail, Option[WorkbenchSubject]]): Option[ErrorReport] = {
    val invalidEmails = emailsToSubjects.collect { case (email, None) => email }
    if (invalidEmails.nonEmpty) {
      val emailCauses = invalidEmails.map { workbenchEmail => ErrorReport(s"Invalid member email: ${workbenchEmail}")}
      Some(ErrorReport(s"You have specified at least one invalid member email", emailCauses.toSeq))
    } else None
  }

  private def validateRoles(resourceType: ResourceType, policy: ValidatableAccessPolicy): Option[ErrorReport] = {
    val invalidRoles = policy.roles -- resourceType.roles.map(_.roleName)
    if (invalidRoles.nonEmpty) {
      val roleCauses = invalidRoles.map { resourceRoleName => ErrorReport(s"Invalid role: ${resourceRoleName}")}
      Some(ErrorReport(s"You have specified an invalid role for resource type ${resourceType.name}. Valid roles are: ${resourceType.roles.map(_.roleName).mkString(", ")}", roleCauses.toSeq))
    } else None
  }

  private def validateActions(resourceType: ResourceType, policy: ValidatableAccessPolicy): Option[ErrorReport] = {
    val invalidActions = policy.actions.filter(a => !resourceType.actionPatterns.exists(_.matches(a)))
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
    directoryDAO.addGroupMember(resourceAndPolicyName, subject).map(_ => ()) andThen {
      case Success(_) => fireGroupUpdateNotification(resourceAndPolicyName)
    }
  }

  def removeSubjectFromPolicy(resourceAndPolicyName: ResourceAndPolicyName, subject: WorkbenchSubject): Future[Unit] = {
    directoryDAO.removeGroupMember(resourceAndPolicyName, subject).map(_ => ()) andThen {
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
    accessPolicyDAO.listAccessPolicies(resource).unsafeToFuture().flatMap { policies =>
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

  private def makeValidatablePolicies(policies: Map[AccessPolicyName, AccessPolicyMembership]): Future[Set[ValidatableAccessPolicy]] = {
    Future.traverse(policies.toList){
      case (accessPolicyName, accessPolicyMembership) => makeCreatablePolicy(accessPolicyName, accessPolicyMembership)
    }.map(_.toSet)
  }

  private def makeCreatablePolicy(accessPolicyName: AccessPolicyName, accessPolicyMembership: AccessPolicyMembership): Future[ValidatableAccessPolicy] = {
    mapEmailsToSubjects(accessPolicyMembership.memberEmails).map { emailsToSubjects =>
      ValidatableAccessPolicy(accessPolicyName, emailsToSubjects, accessPolicyMembership.roles, accessPolicyMembership.actions)
    }
  }

  private def generateGroupEmail() = WorkbenchEmail(s"policy-${UUID.randomUUID}@$emailDomain")
}
