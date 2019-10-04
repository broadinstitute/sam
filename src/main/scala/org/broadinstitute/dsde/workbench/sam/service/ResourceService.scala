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

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success
import io.opencensus.scala.Tracing._
import io.opencensus.trace.Span
import org.broadinstitute.dsde.workbench.sam.util.OpenCensusUtils

/**
  * Created by mbemis on 5/22/17.
  */
class ResourceService(
    private val resourceTypes: Map[ResourceTypeName, ResourceType],
    private[service] val policyEvaluatorService: PolicyEvaluatorService,
    private val accessPolicyDAO: AccessPolicyDAO,
    private val directoryDAO: DirectoryDAO,
    private val cloudExtensions: CloudExtensions,
    private val emailDomain: String)(implicit val executionContext: ExecutionContext)
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
  def initResourceTypes(): IO[Iterable[ResourceType]] =
    resourceTypes.get(SamResourceTypes.resourceTypeAdminName) match {
      case None =>
        IO.raiseError(new WorkbenchException(s"Could not initialize resource types because ${SamResourceTypes.resourceTypeAdminName.value} does not exist."))
      case Some(resourceTypeAdmin) =>
        for {
          // make sure resource type admin is added first because the rest depends on it
          createdAdminType <- createResourceType(resourceTypeAdmin)

          result <- resourceTypes.values.filterNot(_.name == SamResourceTypes.resourceTypeAdminName).toList.parTraverse { rt =>
            for {
              _ <- createResourceType(rt)
              policy = ValidatableAccessPolicy(
                AccessPolicyName(resourceTypeAdmin.ownerRoleName.value),
                Map.empty,
                Set(resourceTypeAdmin.ownerRoleName),
                Set.empty)
              // note that this skips all validations and just creates a resource with owner policies with no members
              // it will require someone with direct ldap access to bootstrap
              _ <- IO.fromFuture(IO(persistResource(resourceTypeAdmin, ResourceId(rt.name.value), Set(policy), Set.empty))).recover {
                case e: WorkbenchExceptionWithErrorReport if e.errorReport.statusCode.contains(StatusCodes.Conflict) =>
                  // ok if the resource already exists
                  Resource(rt.name, ResourceId(rt.name.value), Set.empty)
              }
            } yield rt
          }
        } yield result :+ resourceTypeAdmin
    }

  def createResourceType(resourceType: ResourceType): IO[ResourceType] =
    accessPolicyDAO.createResourceType(resourceType)

  /**
    * Create a resource with default policies. The default policies contain 1 policy with the same name as the
    * owner role for the resourceType, has the owner role, membership contains only userInfo
    * @param resourceType
    * @param resourceId
    * @param userInfo
    * @return
    */
  def createResource(resourceType: ResourceType, resourceId: ResourceId, userInfo: UserInfo): Future[Resource] = {
    val ownerRole = resourceType.roles
      .find(_.roleName == resourceType.ownerRoleName)
      .getOrElse(throw new WorkbenchException(s"owner role ${resourceType.ownerRoleName} does not exist in $resourceType"))
    val defaultPolicies: Map[AccessPolicyName, AccessPolicyMembership] = Map(
      AccessPolicyName(ownerRole.roleName.value) -> AccessPolicyMembership(Set(userInfo.userEmail), Set.empty, Set(ownerRole.roleName)))
    createResource(resourceType, resourceId, defaultPolicies, Set.empty, userInfo.userId)
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
  def createResource(
      resourceType: ResourceType,
      resourceId: ResourceId,
      policiesMap: Map[AccessPolicyName, AccessPolicyMembership],
      authDomain: Set[WorkbenchGroupName],
      userId: WorkbenchUserId): Future[Resource] =
    trace("createResource")( span => makeValidatablePolicies(policiesMap).flatMap { policies =>
      traceWithParent("validateCreateResource", span)( s2 => validateCreateResource(resourceType, resourceId, policies, authDomain, userId, s2)).flatMap {
        case Seq() => traceWithParent("persistResource", span)( s2 => persistResource(resourceType, resourceId, policies, authDomain,s2))
        case errorReports: Seq[ErrorReport] =>
          throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "Cannot create resource", errorReports))
      }
    })

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
  private def persistResource(resourceType: ResourceType, resourceId: ResourceId, policies: Set[ValidatableAccessPolicy], authDomain: Set[WorkbenchGroupName], span: Span = null) =
    for {
      resource <- traceWithParent("createResource", span)( _ => accessPolicyDAO.createResource(Resource(resourceType.name, resourceId, authDomain)).unsafeToFuture())
      _ <- Future.traverse(policies)(p => traceWithParent("createOrUpdatePolicy", span)( _ => createOrUpdatePolicy(FullyQualifiedPolicyId(resource.fullyQualifiedId, p.policyName), p)))
    } yield resource

  private def validateCreateResource(
      resourceType: ResourceType,
      resourceId: ResourceId,
      policies: Set[ValidatableAccessPolicy],
      authDomain: Set[WorkbenchGroupName],
      userId: WorkbenchUserId,
      span: Span = null) =
    for {
      resourceIdErrors <- traceWithParent("validate", span)(_ => Future.successful(validateUrlSafe(resourceId.value)))
      ownerPolicyErrors <- traceWithParent("validate", span)(_ => Future.successful(validateOwnerPolicyExists(resourceType, policies)))
      policyErrors <- traceWithParent("validate", span)(_ => (Future.successful(policies.flatMap(policy => validatePolicy(resourceType, policy)))))
      authDomainErrors <- traceWithParent("validate", span)(_ => validateAuthDomain(resourceType, authDomain, userId))
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

  private def validateAuthDomain(resourceType: ResourceType, authDomain: Set[WorkbenchGroupName], userId: WorkbenchUserId): Future[Option[ErrorReport]] =
    validateAuthDomainPermissions(authDomain, userId).map { permissionsErrors =>
      val constrainableErrors = validateAuthDomainConstraints(resourceType, authDomain).toSeq
      val errors = constrainableErrors ++ permissionsErrors.flatten
      if (errors.nonEmpty) {
        Option(ErrorReport("Invalid Auth Domain specified", errors))
      } else None
    }

  private def validateAuthDomainPermissions(authDomain: Set[WorkbenchGroupName], userId: WorkbenchUserId): Future[Set[Option[ErrorReport]]] =
    Future.traverse(authDomain) { groupName =>
      val resource = FullyQualifiedResourceId(ManagedGroupService.managedGroupTypeName, ResourceId(groupName.value))
      policyEvaluatorService.hasPermission(resource, ManagedGroupService.useAction, userId).unsafeToFuture().map {
        case false => Option(ErrorReport(s"You do not have access to $groupName or $groupName does not exist"))
        case _ => None
      }
    }

  private def validateAuthDomainConstraints(resourceType: ResourceType, authDomain: Set[WorkbenchGroupName]): Option[ErrorReport] =
    if (authDomain.nonEmpty && !resourceType.isAuthDomainConstrainable) {
      Option(ErrorReport(s"Auth Domain is not permitted on resource of type: ${resourceType.name}"))
    } else None

  def loadResourceAuthDomain(resource: FullyQualifiedResourceId): IO[Set[WorkbenchGroupName]] =
    accessPolicyDAO.loadResourceAuthDomain(resource).flatMap(result => result match {
      case LoadResourceAuthDomainResult.Constrained(authDomain) => IO.pure(authDomain.toList.toSet)
      case LoadResourceAuthDomainResult.NotConstrained => IO.pure(Set.empty)
      case LoadResourceAuthDomainResult.ResourceNotFound => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Resource ${resource} not found")))
    })

  def createPolicy(
      policyIdentity: FullyQualifiedPolicyId,
      members: Set[WorkbenchSubject],
      roles: Set[ResourceRoleName],
      actions: Set[ResourceAction]): Future[AccessPolicy] =
    createPolicy(policyIdentity, members, generateGroupEmail(), roles, actions)

  def createPolicy(
      policyIdentity: FullyQualifiedPolicyId,
      members: Set[WorkbenchSubject],
      email: WorkbenchEmail,
      roles: Set[ResourceRoleName],
      actions: Set[ResourceAction]): Future[AccessPolicy] =
    accessPolicyDAO.createPolicy(AccessPolicy(policyIdentity, members, email, roles, actions, public = false)).unsafeToFuture()

  // IF Resource ID reuse is allowed (as defined by the Resource Type), then we can delete the resource
  // ELSE Resource ID reuse is not allowed, and we enforce this by deleting all policies associated with the Resource,
  //      but not the Resource itself, thereby orphaning the Resource so that it cannot be used or accessed anymore and
  //      preventing a new Resource with the same ID from being created
  def deleteResource(resource: FullyQualifiedResourceId): Future[Unit] =
    for {
      policiesToDelete <- accessPolicyDAO.listAccessPolicies(resource).unsafeToFuture()
      // remove from cloud extensions first so a failure there does not leave ldap in a bad state
      _ <- Future.traverse(policiesToDelete) { policy =>
        cloudExtensions.onGroupDelete(policy.email)
      }
      _ <- policiesToDelete.toList.parTraverse(p => accessPolicyDAO.deletePolicy(p.id)).unsafeToFuture()
      _ <- maybeDeleteResource(resource)
    } yield ()

  private def maybeDeleteResource(resource: FullyQualifiedResourceId): Future[Unit] =
    resourceTypes.get(resource.resourceTypeName) match {
      case Some(resourceType) if resourceType.reuseIds => accessPolicyDAO.deleteResource(resource).unsafeToFuture()
      case _ => Future.successful(())
    }

  def listUserResourceRoles(resource: FullyQualifiedResourceId, userInfo: UserInfo): Future[Set[ResourceRoleName]] =
    policyEvaluatorService
      .listResourceAccessPoliciesForUser(resource, userInfo.userId)
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
      policyMembership: AccessPolicyMembership): Future[AccessPolicy] =
    makeCreatablePolicy(policyName, policyMembership).flatMap { policy =>
      validatePolicy(resourceType, policy) match {
        case Some(errorReport) =>
          throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "You have specified an invalid policy", errorReport))
        case None => createOrUpdatePolicy(FullyQualifiedPolicyId(resource, policyName), policy)
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
      membersList: Set[WorkbenchEmail]): Future[Unit] =
    mapEmailsToSubjects(membersList).flatMap { emailsToSubjects =>
      validateMemberEmails(emailsToSubjects) match {
        case Some(error) => Future.failed(new WorkbenchExceptionWithErrorReport(error))
        case None =>
          accessPolicyDAO.overwritePolicyMembers(model.FullyQualifiedPolicyId(resource, policyName), emailsToSubjects.values.flatten.toSet).unsafeToFuture()
      }
    }

  /**
    * Overwrites the policy if it already exists or creates a new policy entry if it does not exist.
    * Triggers update to Google Group upon successfully updating the policy.
    * Note: This method DOES NOT validate the policy and should probably not be called directly unless you know the
    * contents are valid.  To validate and save the policy, use overwritePolicy()
    * Note: this function DOES NOT update the email or public fields of a policy
    * @param resource
    * @param policy
    * @return
    */
  private def createOrUpdatePolicy(policyIdentity: FullyQualifiedPolicyId, policy: ValidatableAccessPolicy): Future[AccessPolicy] = {
    val workbenchSubjects = policy.emailsToSubjects.values.flatten.toSet
    accessPolicyDAO.loadPolicy(policyIdentity).unsafeToFuture().flatMap {
      case None => createPolicy(policyIdentity, workbenchSubjects, generateGroupEmail(), policy.roles, policy.actions)
      case Some(accessPolicy) =>
        accessPolicyDAO
          .overwritePolicy(AccessPolicy(policyIdentity, workbenchSubjects, accessPolicy.email, policy.roles, policy.actions, accessPolicy.public))
          .unsafeToFuture().andThen {
            case Success(_) => fireGroupUpdateNotification(policyIdentity)
          }
    }
  }

  private def mapEmailsToSubjects(workbenchEmails: Set[WorkbenchEmail]): Future[Map[WorkbenchEmail, Option[WorkbenchSubject]]] = {
    val eventualSubjects = workbenchEmails.map { workbenchEmail =>
      directoryDAO.loadSubjectFromEmail(workbenchEmail).unsafeToFuture().map(workbenchEmail -> _)
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

  private def fireGroupUpdateNotification(groupId: WorkbenchGroupIdentity): Future[Unit] =
    cloudExtensions.onGroupUpdate(Seq(groupId)) recover {
      case t: Throwable =>
        logger.error(s"error calling cloudExtensions.onGroupUpdate for $groupId", t)
        throw t
    }

  def addSubjectToPolicy(policyIdentity: FullyQualifiedPolicyId, subject: WorkbenchSubject): Future[Unit] = {
    subject match {
      case subject: FullyQualifiedPolicyId if policyIdentity.resource.resourceTypeName.equals(ManagedGroupService.managedGroupTypeName) =>
        Future.failed(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"Access policies cannot be added to managed groups.")))
      case _ =>    {
        directoryDAO.addGroupMember(policyIdentity, subject).unsafeToFuture().map(_ => ()) andThen {
          case Success(_) => fireGroupUpdateNotification(policyIdentity)
        }
      }
    }
  }

  def removeSubjectFromPolicy(policyIdentity: FullyQualifiedPolicyId, subject: WorkbenchSubject): Future[Unit] =
    directoryDAO.removeGroupMember(policyIdentity, subject).void.unsafeToFuture() andThen {
      case Success(_) => fireGroupUpdateNotification(policyIdentity)
    }

  private[service] def loadAccessPolicyWithEmails(policy: AccessPolicy): IO[AccessPolicyMembership] = {
    val users = policy.members.collect { case userId: WorkbenchUserId => userId }
    val groups = policy.members.collect { case groupName: WorkbenchGroupName => groupName }
    val policyMembers = policy.members.collect { case policyId: FullyQualifiedPolicyId => policyId }

    for {
      userEmails <- directoryDAO.loadUsers(users)
      groupEmails <- directoryDAO.loadGroups(groups)
      policyEmails <- policyMembers.toList.parTraverse(accessPolicyDAO.loadPolicy(_))
    } yield
      AccessPolicyMembership(
        userEmails.toSet[WorkbenchUser].map(_.email) ++ groupEmails.map(_.email) ++ policyEmails.flatMap(_.map(_.email)),
        policy.actions,
        policy.roles)
  }

  val emptyMembership = AccessPolicyMembership(Set(), Set(), Set())

  def listResourcePolicies(resource: FullyQualifiedResourceId, loadMembers: Boolean = true): IO[Stream[AccessPolicyResponseEntry]] =
    OpenCensusUtils.traceIO("listAccessPolicies" )(span => accessPolicyDAO.listAccessPolicies(resource).flatMap { policies =>
        policies.parTraverse { policy =>
          OpenCensusUtils.traceIOWithParent(s"loadAccessPolicyWithEmails-${policy.email}", span)(_ =>
            if (loadMembers) {
              loadAccessPolicyWithEmails(policy).map { membership => AccessPolicyResponseEntry(policy.id.accessPolicyName, membership, policy.email) }
            } else {
              IO.pure(AccessPolicyResponseEntry(policy.id.accessPolicyName, emptyMembership, policy.email))
            }
          )
        }
      }
    )

  def loadResourcePolicy(policyIdentity: FullyQualifiedPolicyId): IO[Option[AccessPolicyMembership]] =
    for {
      policy <- accessPolicyDAO.loadPolicy(policyIdentity)
      res <- policy.traverse(p => loadAccessPolicyWithEmails(p))
    } yield res

  private def makeValidatablePolicies(policies: Map[AccessPolicyName, AccessPolicyMembership]): Future[Set[ValidatableAccessPolicy]] =
    Future
      .traverse(policies.toList) {
        case (accessPolicyName, accessPolicyMembership) => makeCreatablePolicy(accessPolicyName, accessPolicyMembership)
      }
      .map(_.toSet)

  private def makeCreatablePolicy(accessPolicyName: AccessPolicyName, accessPolicyMembership: AccessPolicyMembership): Future[ValidatableAccessPolicy] =
    mapEmailsToSubjects(accessPolicyMembership.memberEmails).map { emailsToSubjects =>
      ValidatableAccessPolicy(accessPolicyName, emailsToSubjects, accessPolicyMembership.roles, accessPolicyMembership.actions)
    }

  private def generateGroupEmail() = WorkbenchEmail(s"policy-${UUID.randomUUID}@$emailDomain")

  def isPublic(resourceAndPolicyName: FullyQualifiedPolicyId): IO[Boolean] =
    accessPolicyDAO.loadPolicy(resourceAndPolicyName).flatMap {
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
  def setPublic(policyId: FullyQualifiedPolicyId, public: Boolean): IO[Unit] =
    for {
      authDomain <- accessPolicyDAO.loadResourceAuthDomain(policyId.resource)
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
          accessPolicyDAO.setPolicyIsPublic(policyId, public) *> IO.fromFuture(IO(fireGroupUpdateNotification(policyId)))
      }
    } yield ()

  def listAllFlattenedResourceUsers(resourceId: FullyQualifiedResourceId): IO[Set[UserIdInfo]] =
    for {
      accessPolicies <- accessPolicyDAO.listAccessPolicies(resourceId)
      members <- accessPolicies.toList.parTraverse(accessPolicy => accessPolicyDAO.listFlattenedPolicyMembers(accessPolicy.id))
      workbenchUsers = members.flatten.toSet
    } yield {
      workbenchUsers.map(user => UserIdInfo(user.id, user.email, user.googleSubjectId))
    }
}
