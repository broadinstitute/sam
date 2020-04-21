package org.broadinstitute.dsde.workbench.sam.service

import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.{model, _}
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.{AccessPolicyDAO, LoadResourceAuthDomainResult}
import org.broadinstitute.dsde.workbench.sam.util.TraceContext

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success
import scala.concurrent.duration._

/**
  * Created by mbemis on 5/22/17.
  */
class ResourceService(
    private val resourceTypes: Map[ResourceTypeName, ResourceType],
    private[service] val policyEvaluatorService: PolicyEvaluatorService,
    private val accessPolicyDAO: AccessPolicyDAO,
    private val directoryDAO: DirectoryDAO,
    private val cloudExtensions: CloudExtensions,
    val emailDomain: String)(implicit val executionContext: ExecutionContext)
    extends LazyLogging {
  implicit val cs = IO.contextShift(executionContext) //for running IOs in paralell

  private case class ValidatableAccessPolicy(
      policyName: AccessPolicyName,
      emailsToSubjects: Map[WorkbenchEmail, Option[WorkbenchSubject]],
      roles: Set[ResourceRoleName],
      actions: Set[ResourceAction])

  def getResourceTypes(): IO[Map[ResourceTypeName, ResourceType]] =
    IO.pure(resourceTypes)

  def getResourceType(name: ResourceTypeName): IO[Option[ResourceType]] =
    IO.pure(resourceTypes.get(name))

  /**
    * Creates each resource type in ldap and creates a resource for each with the resource type SamResourceTypes.resourceTypeAdmin.
    *
    * This will fail if SamResourceTypes.resourceTypeAdmin does not exist in resourceTypes
    */
  def initResourceTypes(traceContext: TraceContext = null): IO[Iterable[ResourceType]] =
    resourceTypes.get(SamResourceTypes.resourceTypeAdminName) match {
      case None =>
        IO.raiseError(new WorkbenchException(s"Could not initialize resource types because ${SamResourceTypes.resourceTypeAdminName.value} does not exist."))
      case Some(resourceTypeAdmin) =>
        for {
          // make sure resource type admin is added first because the rest depends on it
          createdAdminType <- createResourceType(resourceTypeAdmin, traceContext)

          // sleep added so shadow dao can catch up, remove when removing opendj
          // https://broadworkbench.atlassian.net/browse/CA-526
          _ <- IO.sleep(1 second)(IO.timer(executionContext))

          result <- resourceTypes.values.filterNot(_.name == SamResourceTypes.resourceTypeAdminName).toList.traverse { rt =>
            for {
              _ <- createResourceType(rt, traceContext)

              // sleep added so shadow dao can catch up, remove when removing opendj
              // https://broadworkbench.atlassian.net/browse/CA-526
              _ <- IO.sleep(1 second)(IO.timer(executionContext))

              policy = ValidatableAccessPolicy(
                AccessPolicyName(resourceTypeAdmin.ownerRoleName.value),
                Map.empty,
                Set(resourceTypeAdmin.ownerRoleName),
                Set.empty)
              // note that this skips all validations and just creates a resource with owner policies with no members
              // it will require someone with direct ldap access to bootstrap
              _ <- persistResource(resourceTypeAdmin, ResourceId(rt.name.value), Set(policy), Set.empty, traceContext).recover {
                case e: WorkbenchExceptionWithErrorReport if e.errorReport.statusCode.contains(StatusCodes.Conflict) =>
                  // ok if the resource already exists
                  Resource(rt.name, ResourceId(rt.name.value), Set.empty)
              }
            } yield rt
          }
        } yield result :+ resourceTypeAdmin
    }

  def createResourceType(resourceType: ResourceType, traceContext: TraceContext): IO[ResourceType] =
    accessPolicyDAO.createResourceType(resourceType, traceContext)

  /**
    * Create a resource with default policies. The default policies contain 1 policy with the same name as the
    * owner role for the resourceType, has the owner role, membership contains only userInfo
    * @param resourceType
    * @param resourceId
    * @param userInfo
    * @return
    */
  def createResource(resourceType: ResourceType, resourceId: ResourceId, userInfo: UserInfo, traceContext: TraceContext): IO[Resource] = {
    val ownerRole = resourceType.roles
      .find(_.roleName == resourceType.ownerRoleName)
      .getOrElse(throw new WorkbenchException(s"owner role ${resourceType.ownerRoleName} does not exist in $resourceType"))
    val defaultPolicies: Map[AccessPolicyName, AccessPolicyMembership] = Map(
      AccessPolicyName(ownerRole.roleName.value) -> AccessPolicyMembership(Set(userInfo.userEmail), Set.empty, Set(ownerRole.roleName)))
    createResource(resourceType, resourceId, defaultPolicies, Set.empty, userInfo.userId, traceContext)
  }

  /**
    * Validates the resource first and if any validations fail, an exception is thrown with an error report that
    * describes what failed.  If validations pass, then the Resource should be persisted.
    * @param resourceType
    * @param resourceId
    * @param policiesMap
    * @param userId
    * @return Future[Resource]
    */
  def createResource(
      resourceType: ResourceType,
      resourceId: ResourceId,
      policiesMap: Map[AccessPolicyName, AccessPolicyMembership],
      authDomain: Set[WorkbenchGroupName],
      userId: WorkbenchUserId,
      traceContext: TraceContext): IO[Resource] =
    makeValidatablePolicies(policiesMap, traceContext).flatMap { policies =>
      validateCreateResource(resourceType, resourceId, policies, authDomain, userId, traceContext).flatMap {
        case Seq() => persistResource(resourceType, resourceId, policies, authDomain, traceContext)
        case errorReports: Seq[ErrorReport] =>
          throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "Cannot create resource", errorReports))
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
  private def persistResource(resourceType: ResourceType, resourceId: ResourceId, policies: Set[ValidatableAccessPolicy], authDomain: Set[WorkbenchGroupName], traceContext: TraceContext) =
    for {
      resource <- accessPolicyDAO.createResource(Resource(resourceType.name, resourceId, authDomain), traceContext)
      policies <- policies.toList.traverse(p => createOrUpdatePolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, p.policyName), p, traceContext))
    } yield resource.copy(accessPolicies=policies.toSet)

  private def validateCreateResource(
      resourceType: ResourceType,
      resourceId: ResourceId,
      policies: Set[ValidatableAccessPolicy],
      authDomain: Set[WorkbenchGroupName],
      userId: WorkbenchUserId,
      traceContext: TraceContext) =
    for {
      resourceIdErrors <- IO.pure(validateUrlSafe(resourceId.value))
      ownerPolicyErrors <- IO.pure(validateOwnerPolicyExists(resourceType, policies))
      policyErrors <- IO.pure(policies.flatMap(policy => validatePolicy(resourceType, policy)))
      authDomainErrors <- validateAuthDomain(resourceType, authDomain, userId, traceContext)
    } yield (resourceIdErrors ++ ownerPolicyErrors ++ policyErrors ++ authDomainErrors).toSeq

  private val validUrlSafePattern = "[-a-zA-Z0-9._~%]+".r
  private def validateUrlSafe(input: String): Option[ErrorReport] = {
    if(! validUrlSafePattern.pattern.matcher(input).matches) {
      Option(ErrorReport(s"Invalid input: $input. Valid characters are alphanumeric characters, periods, tildes, percents, underscores, and dashes. Try url encoding."))
    } else {
      None
    }
  }

  private def validateOwnerPolicyExists(resourceType: ResourceType, policies: Set[ValidatableAccessPolicy]): Option[ErrorReport] =
    policies.exists { policy =>
      policy.roles.contains(resourceType.ownerRoleName) && policy.emailsToSubjects.nonEmpty
    } match {
      case true => None
      case false =>
        Option(ErrorReport(s"Cannot create resource without at least 1 policy with ${resourceType.ownerRoleName.value} role and non-empty membership"))
    }

  private def validateAuthDomain(resourceType: ResourceType, authDomain: Set[WorkbenchGroupName], userId: WorkbenchUserId, traceContext: TraceContext): IO[Option[ErrorReport]] =
    validateAuthDomainPermissions(authDomain, userId, traceContext).map { permissionsErrors =>
      val constrainableErrors = validateAuthDomainConstraints(resourceType, authDomain).toSeq
      val errors = constrainableErrors ++ permissionsErrors.flatten
      if (errors.nonEmpty) {
        Option(ErrorReport("Invalid Auth Domain specified", errors))
      } else None
    }

  private def validateAuthDomainPermissions(authDomain: Set[WorkbenchGroupName], userId: WorkbenchUserId, traceContext: TraceContext): IO[List[Option[ErrorReport]]] =
    authDomain.toList.traverse { groupName =>
      val resource = FullyQualifiedResourceId(ManagedGroupService.managedGroupTypeName, ResourceId(groupName.value))
      policyEvaluatorService.hasPermission(resource, ManagedGroupService.useAction, userId, traceContext).map {
        case false => Option(ErrorReport(s"You do not have access to $groupName or $groupName does not exist"))
        case _ => None
      }
    }

  private def validateAuthDomainConstraints(resourceType: ResourceType, authDomain: Set[WorkbenchGroupName]): Option[ErrorReport] =
    if (authDomain.nonEmpty && !resourceType.isAuthDomainConstrainable) {
      Option(ErrorReport(s"Auth Domain is not permitted on resource of type: ${resourceType.name}"))
    } else None

  def loadResourceAuthDomain(resource: FullyQualifiedResourceId, traceContext: TraceContext): IO[Set[WorkbenchGroupName]] =
    accessPolicyDAO.loadResourceAuthDomain(resource, traceContext).flatMap(result => result match {
      case LoadResourceAuthDomainResult.Constrained(authDomain) => IO.pure(authDomain.toList.toSet)
      case LoadResourceAuthDomainResult.NotConstrained => IO.pure(Set.empty)
      case LoadResourceAuthDomainResult.ResourceNotFound => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Resource ${resource} not found")))
    })

  def createPolicy(
      policyIdentity: FullyQualifiedPolicyId,
      members: Set[WorkbenchSubject],
      roles: Set[ResourceRoleName],
      actions: Set[ResourceAction],
      traceContext: TraceContext): IO[AccessPolicy] =
    createPolicy(policyIdentity, members, generateGroupEmail(), roles, actions, traceContext)

  def createPolicy(
      policyIdentity: FullyQualifiedPolicyId,
      members: Set[WorkbenchSubject],
      email: WorkbenchEmail,
      roles: Set[ResourceRoleName],
      actions: Set[ResourceAction],
      traceContext: TraceContext): IO[AccessPolicy] =
    accessPolicyDAO.createPolicy(AccessPolicy(policyIdentity, members, email, roles, actions, public = false), traceContext)

  // IF Resource ID reuse is allowed (as defined by the Resource Type), then we can delete the resource
  // ELSE Resource ID reuse is not allowed, and we enforce this by deleting all policies associated with the Resource,
  //      but not the Resource itself, thereby orphaning the Resource so that it cannot be used or accessed anymore and
  //      preventing a new Resource with the same ID from being created
  def deleteResource(resource: FullyQualifiedResourceId, traceContext: TraceContext): Future[Unit] =
    for {
      // remove from cloud extensions first so a failure there does not leave ldap in a bad state
      policiesToDelete <- cloudDeletePolicies(resource, traceContext)

      _ <- policiesToDelete.toList.parTraverse(p => accessPolicyDAO.deletePolicy(p.id, traceContext)).unsafeToFuture()
      _ <- maybeDeleteResource(resource, traceContext)
    } yield ()

  def cloudDeletePolicies(resource: FullyQualifiedResourceId, traceContext: TraceContext): Future[Stream[AccessPolicy]] = {
    for {
      policiesToDelete <- accessPolicyDAO.listAccessPolicies(resource, traceContext).unsafeToFuture()
      _ <- Future.traverse(policiesToDelete) { policy =>
        cloudExtensions.onGroupDelete(policy.email)
      }
    } yield policiesToDelete
  }

  private def maybeDeleteResource(resource: FullyQualifiedResourceId, traceContext: TraceContext): Future[Unit] =
    resourceTypes.get(resource.resourceTypeName) match {
      case Some(resourceType) if resourceType.reuseIds => accessPolicyDAO.deleteResource(resource, traceContext).unsafeToFuture()
      case _ => accessPolicyDAO.removeAuthDomainFromResource(resource, traceContext).unsafeToFuture()
    }

  def listUserResourceRoles(resource: FullyQualifiedResourceId, userInfo: UserInfo, traceContext: TraceContext): Future[Set[ResourceRoleName]] =
    policyEvaluatorService
      .listResourceAccessPoliciesForUser(resource, userInfo.userId, traceContext)
      .map { matchingPolicies =>
        matchingPolicies.flatMap(_.roles)
      }
      .unsafeToFuture()

  /**
    * Overwrites an existing policy (keyed by resourceType/resourceId/policyName), saves a new one if it doesn't exist yet
    * @param resourceType
    * @param policyName
    * @param resource
    * @param policyMembership
    * @return
    */
  def overwritePolicy(
      resourceType: ResourceType,
      policyName: AccessPolicyName,
      resource: FullyQualifiedResourceId,
      policyMembership: AccessPolicyMembership,
      traceContext: TraceContext): IO[AccessPolicy] =
    makeCreatablePolicy(policyName, policyMembership, traceContext).flatMap { policy =>
      validatePolicy(resourceType, policy) match {
        case Some(errorReport) =>
          throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "You have specified an invalid policy", errorReport))
        case None => createOrUpdatePolicy(FullyQualifiedPolicyId(resource, policyName), policy, traceContext)
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
  def overwritePolicyMembers(
      resourceType: ResourceType,
      policyName: AccessPolicyName,
      resource: FullyQualifiedResourceId,
      membersList: Set[WorkbenchEmail],
      traceContext: TraceContext): IO[Unit] =
    mapEmailsToSubjects(membersList, traceContext).flatMap { emailsToSubjects =>
      validateMemberEmails(emailsToSubjects) match {
        case Some(error) => IO.raiseError(new WorkbenchExceptionWithErrorReport(error))
        case None =>
          accessPolicyDAO.overwritePolicyMembers(model.FullyQualifiedPolicyId(resource, policyName), emailsToSubjects.values.flatten.toSet, traceContext)
      }
    }

  /**
    * Overwrites the policy if it already exists or creates a new policy entry if it does not exist.
    * Triggers update to Google Group upon successfully updating the policy.
    * Note: This method DOES NOT validate the policy and should probably not be called directly unless you know the
    * contents are valid.  To validate and save the policy, use overwritePolicy()
    * Note: this function DOES NOT update the email or public fields of a policy
    * @param policyIdentity
    * @param policy
    * @return
    */
  private def createOrUpdatePolicy(policyIdentity: FullyQualifiedPolicyId, policy: ValidatableAccessPolicy, traceContext: TraceContext): IO[AccessPolicy] = {
    val workbenchSubjects = policy.emailsToSubjects.values.flatten.toSet
    accessPolicyDAO.loadPolicy(policyIdentity, traceContext).flatMap {
      case None => createPolicy(policyIdentity, workbenchSubjects, generateGroupEmail(), policy.roles, policy.actions, traceContext)
      case Some(accessPolicy) =>
        for {
          result <- accessPolicyDAO.overwritePolicy(AccessPolicy(policyIdentity, workbenchSubjects, accessPolicy.email, policy.roles, policy.actions, accessPolicy.public), traceContext)
          _ <- IO.fromFuture(IO(fireGroupUpdateNotification(policyIdentity, traceContext))).runAsync {
            case Left(regrets) => IO(logger.error(s"failure calling fireGroupUpdateNotification on $policyIdentity", regrets))
            case Right(_) => IO.unit
          }.toIO
        } yield {
          result
        }
    }
  }

  private def mapEmailsToSubjects(workbenchEmails: Set[WorkbenchEmail], traceContext: TraceContext): IO[Map[WorkbenchEmail, Option[WorkbenchSubject]]] = {
    val eventualSubjects = workbenchEmails.map { workbenchEmail =>
      directoryDAO.loadSubjectFromEmail(workbenchEmail, traceContext).map(workbenchEmail -> _)
    }

    eventualSubjects.toList.sequence.map(_.toMap)
  }

  /**
    * Validates a policy in the context of a ResourceType.  When validating the policy, we want to collect each entity
    * that was problematic and report that back using ErrorReports
    * @param resourceType
    * @param policy
    * @return
    */
  private def validatePolicy(resourceType: ResourceType, policy: ValidatableAccessPolicy): Option[ErrorReport] = {
    val validationErrors =
      validateMemberEmails(policy.emailsToSubjects) ++
      validateActions(resourceType, policy) ++
      validateRoles(resourceType, policy) ++
      validateUrlSafe(policy.policyName.value)

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
      val emailCauses = invalidEmails.map { workbenchEmail =>
        ErrorReport(s"Invalid member email: ${workbenchEmail}")
      }
      Some(ErrorReport(s"You have specified at least one invalid member email", emailCauses.toSeq))
    } else None
  }

  private def validateRoles(resourceType: ResourceType, policy: ValidatableAccessPolicy): Option[ErrorReport] = {
    val invalidRoles = policy.roles -- resourceType.roles.map(_.roleName)
    if (invalidRoles.nonEmpty) {
      val roleCauses = invalidRoles.map { resourceRoleName =>
        ErrorReport(s"Invalid role: ${resourceRoleName}")
      }
      Some(
        ErrorReport(
          s"You have specified an invalid role for resource type ${resourceType.name}. Valid roles are: ${resourceType.roles.map(_.roleName).mkString(", ")}",
          roleCauses.toSeq
        ))
    } else None
  }

  private def validateActions(resourceType: ResourceType, policy: ValidatableAccessPolicy): Option[ErrorReport] = {
    val invalidActions = policy.actions.filter(a => !resourceType.actionPatterns.exists(_.matches(a)))
    if (invalidActions.nonEmpty) {
      val actionCauses = invalidActions.map { resourceAction =>
        ErrorReport(s"Invalid action: ${resourceAction}")
      }
      Some(
        ErrorReport(
          s"You have specified an invalid action for resource type ${resourceType.name}. Valid actions are: ${resourceType.actionPatterns.mkString(", ")}",
          actionCauses.toSeq
        ))
    } else None
  }

  private def fireGroupUpdateNotification(groupId: WorkbenchGroupIdentity, traceContext: TraceContext): Future[Unit] =
    cloudExtensions.onGroupUpdate(Seq(groupId), traceContext) recover {
      case t: Throwable =>
        logger.error(s"error calling cloudExtensions.onGroupUpdate for $groupId", t)
        throw t
    }

  def addSubjectToPolicy(policyIdentity: FullyQualifiedPolicyId, subject: WorkbenchSubject, traceContext: TraceContext): Future[Unit] = {
    subject match {
      case subject: FullyQualifiedPolicyId if policyIdentity.resource.resourceTypeName.equals(ManagedGroupService.managedGroupTypeName) =>
        Future.failed(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"Access policies cannot be added to managed groups.")))
      case _ =>    {
        directoryDAO.addGroupMember(policyIdentity, subject, traceContext).unsafeToFuture().map(_ => ()) andThen {
          case Success(_) => fireGroupUpdateNotification(policyIdentity, traceContext)
        }
      }
    }
  }

  def removeSubjectFromPolicy(policyIdentity: FullyQualifiedPolicyId, subject: WorkbenchSubject, traceContext: TraceContext): Future[Unit] =
    directoryDAO.removeGroupMember(policyIdentity, subject, traceContext).void.unsafeToFuture() andThen {
      case Success(_) => fireGroupUpdateNotification(policyIdentity, traceContext)
    }

  private[service] def loadAccessPolicyWithEmails(policy: AccessPolicy, traceContext: TraceContext): IO[AccessPolicyMembership] = {
    val users = policy.members.collect { case userId: WorkbenchUserId => userId }
    val groups = policy.members.collect { case groupName: WorkbenchGroupName => groupName }
    val policyMembers = policy.members.collect { case policyId: FullyQualifiedPolicyId => policyId }

    for {
      userEmails <- directoryDAO.loadUsers(users, traceContext)
      groupEmails <- directoryDAO.loadGroups(groups, traceContext)
      policyEmails <- policyMembers.toList.parTraverse(accessPolicyDAO.loadPolicy(_, traceContext))
    } yield
      AccessPolicyMembership(
        userEmails.toSet[WorkbenchUser].map(_.email) ++ groupEmails.map(_.email) ++ policyEmails.flatMap(_.map(_.email)),
        policy.actions,
        policy.roles)
  }

  def listResourcePolicies(resource: FullyQualifiedResourceId, traceContext: TraceContext): IO[Stream[AccessPolicyResponseEntry]] =
    accessPolicyDAO.listAccessPolicies(resource, traceContext).flatMap { policies =>
      policies.parTraverse { policy =>
        loadAccessPolicyWithEmails(policy, traceContext).map { membership =>
          AccessPolicyResponseEntry(policy.id.accessPolicyName, membership, policy.email)
        }
      }
    }

  def loadResourcePolicy(policyIdentity: FullyQualifiedPolicyId, traceContext: TraceContext): IO[Option[AccessPolicyMembership]] =
    for {
      policy <- accessPolicyDAO.loadPolicy(policyIdentity, traceContext)
      res <- policy.traverse(p => loadAccessPolicyWithEmails(p, traceContext))
    } yield res

  private def makeValidatablePolicies(policies: Map[AccessPolicyName, AccessPolicyMembership], traceContext: TraceContext): IO[Set[ValidatableAccessPolicy]] =
    policies.toList.traverse {
      case (accessPolicyName, accessPolicyMembership) => makeCreatablePolicy(accessPolicyName, accessPolicyMembership, traceContext)
    }.map(_.toSet)

  private def makeCreatablePolicy(accessPolicyName: AccessPolicyName, accessPolicyMembership: AccessPolicyMembership, traceContext: TraceContext): IO[ValidatableAccessPolicy] =
    mapEmailsToSubjects(accessPolicyMembership.memberEmails, traceContext).map { emailsToSubjects =>
      ValidatableAccessPolicy(accessPolicyName, emailsToSubjects, accessPolicyMembership.roles, accessPolicyMembership.actions)
    }

  private def generateGroupEmail() = WorkbenchEmail(s"policy-${UUID.randomUUID}@$emailDomain")

  def isPublic(resourceAndPolicyName: FullyQualifiedPolicyId, traceContext: TraceContext): IO[Boolean] =
    accessPolicyDAO.loadPolicy(resourceAndPolicyName, traceContext).flatMap {
      case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "policy not found")))
      case Some(accessPolicy) => IO.pure(accessPolicy.public)
    }

  /**
    * Sets the public field of a policy. Raises an error if the policy has an auth domain and public == true.
    * Triggers update to Google Group upon successfully updating the policy.
    *
    * @param policyId the fully qualified id of the policy
    * @param public true to make the policy public, false to make it private
    * @return
    */
  def setPublic(policyId: FullyQualifiedPolicyId, public: Boolean, traceContext: TraceContext): IO[Unit] =
    for {
      authDomain <- accessPolicyDAO.loadResourceAuthDomain(policyId.resource, traceContext)
      _ <- authDomain match {
        case LoadResourceAuthDomainResult.ResourceNotFound => IO.raiseError(
          new WorkbenchExceptionWithErrorReport(
            ErrorReport(StatusCodes.BadRequest, s"ResourceId ${policyId.resource} not found.")))
        case LoadResourceAuthDomainResult.Constrained(_) if public =>
          // resources with auth domains logically can't have public policies but also technically allowing them poses a problem
          // because the logic for public resources is different. However, sharing with the auth domain should have the
          // exact same effect as making a policy public: anyone in the auth domain can access.
          IO.raiseError(
            new WorkbenchExceptionWithErrorReport(
              ErrorReport(StatusCodes.BadRequest, "Cannot make auth domain protected resources public. Share directly with auth domain groups instead.")))
        case LoadResourceAuthDomainResult.NotConstrained =>
          accessPolicyDAO.setPolicyIsPublic(policyId, public, traceContext) *> IO.fromFuture(IO(fireGroupUpdateNotification(policyId, traceContext)))
      }
    } yield ()

  def listAllFlattenedResourceUsers(resourceId: FullyQualifiedResourceId, traceContext: TraceContext): IO[Set[UserIdInfo]] =
    for {
      accessPolicies <- accessPolicyDAO.listAccessPolicies(resourceId, traceContext)
      members <- accessPolicies.toList.parTraverse(accessPolicy => accessPolicyDAO.listFlattenedPolicyMembers(accessPolicy.id, traceContext))
      workbenchUsers = members.flatten.toSet
    } yield {
      workbenchUsers.map(user => UserIdInfo(user.id, user.email, user.googleSubjectId))
    }
}
