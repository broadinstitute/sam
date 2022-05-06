package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO, LoadResourceAuthDomainResult}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

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

  private[service] case class ValidatableAccessPolicy(
                                              policyName: AccessPolicyName,
                                              emailsToSubjects: Map[WorkbenchEmail, Option[WorkbenchSubject]],
                                              roles: Set[ResourceRoleName],
                                              actions: Set[ResourceAction],
                                              descendantPermissions: Set[AccessPolicyDescendantPermissions])

  def getResourceTypes(): IO[Map[ResourceTypeName, ResourceType]] =
    IO.pure(resourceTypes)

  def getResourceType(name: ResourceTypeName): IO[Option[ResourceType]] =
    IO.pure(resourceTypes.get(name))

  /**
    * Creates each resource type in ldap and creates a resource for each with the resource type SamResourceTypes.resourceTypeAdmin.
    *
    * This will fail if SamResourceTypes.resourceTypeAdmin does not exist in resourceTypes
    */
  def initResourceTypes(samRequestContext: SamRequestContext = SamRequestContext(None)): IO[Iterable[ResourceType]] = // `SamRequestContext(None)` is used so that we don't trace 1-off boot/init methods
    resourceTypes.get(SamResourceTypes.resourceTypeAdminName) match {
      case None =>
        IO.raiseError(new WorkbenchException(s"Could not initialize resource types because ${SamResourceTypes.resourceTypeAdminName.value} does not exist."))
      case Some(resourceTypeAdmin) =>
        for {
          newOrUpdatedResourceTypeNames <- accessPolicyDAO.upsertResourceTypes(resourceTypes.values.toSet, samRequestContext)

          // ensure a resourceTypeAdmin resource exists for each new/update resource type (except resourceTypeAdmin)
          _ <- newOrUpdatedResourceTypeNames.filterNot(_ == SamResourceTypes.resourceTypeAdminName).toList.traverse { rtName =>
            val policy = ValidatableAccessPolicy(
              AccessPolicyName(resourceTypeAdmin.ownerRoleName.value),
              Map.empty,
              Set(resourceTypeAdmin.ownerRoleName),
              Set.empty,
              Set.empty)
            // note that this skips all validations and just creates a resource with owner policies with no members
            // it will require someone with direct database access to bootstrap
            persistResource(resourceTypeAdmin, ResourceId(rtName.value), Set(policy), Set.empty, None, samRequestContext).recover {
              case e: WorkbenchExceptionWithErrorReport if e.errorReport.statusCode.contains(StatusCodes.Conflict) =>
                // ok if the resource already exists
                Resource(resourceTypeAdmin.name, ResourceId(rtName.value), Set.empty)
            }
          }
        } yield resourceTypes.values
    }

  def createResourceType(resourceType: ResourceType, samRequestContext: SamRequestContext): IO[ResourceType] =
    accessPolicyDAO.createResourceType(resourceType, samRequestContext)

  /**
    * Create a resource with default policies. The default policies contain 1 policy with the same name as the
    * owner role for the resourceType, has the owner role, membership contains only userInfo
    *
    * @param resourceType
    * @param resourceId
    * @param userInfo
    * @return
    */
  def createResource(resourceType: ResourceType, resourceId: ResourceId, userInfo: SamUser, samRequestContext: SamRequestContext): IO[Resource] = {
    val ownerRole = resourceType.roles
      .find(_.roleName == resourceType.ownerRoleName)
      .getOrElse(throw new WorkbenchException(s"owner role ${resourceType.ownerRoleName} does not exist in $resourceType"))
    val defaultPolicies: Map[AccessPolicyName, AccessPolicyMembership] = Map(
      AccessPolicyName(ownerRole.roleName.value) -> AccessPolicyMembership(Set(userInfo.email), Set.empty, Set(ownerRole.roleName), None, None))
    createResource(resourceType, resourceId, defaultPolicies, Set.empty, None, userInfo.id, samRequestContext)
  }

  /**
    * Validates the resource first and if any validations fail, an exception is thrown with an error report that
    * describes what failed.  If validations pass, then the Resource should be persisted.
    *
    * @param resourceType
    * @param resourceId
    * @param policiesMap
    * @param userId
    * @return Future[Resource]
    */
  def createResource(resourceType: ResourceType, resourceId: ResourceId, policiesMap: Map[AccessPolicyName, AccessPolicyMembership], authDomain: Set[WorkbenchGroupName], parentOpt: Option[FullyQualifiedResourceId], userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Resource] = {
    logger.info(s"Creating new `${resourceType.name}` with resourceId: `${resourceId}`")
    makeValidatablePolicies(policiesMap, samRequestContext).flatMap { policies =>
      validateCreateResource(resourceType, resourceId, policies, authDomain, userId, parentOpt, samRequestContext).flatMap {
        case Seq() => persistResource(resourceType, resourceId, policies, authDomain, parentOpt, samRequestContext)
        case errorReports: Seq[ErrorReport] =>
          throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "Cannot create resource", errorReports))
      }
    }
  }

  /**
    * This method only persists the resource and then overwrites/creates the policies for that resource.
    * Be very careful if calling this method directly because it will not validate the resource or its policies.
    * If you want to create a Resource, use createResource() which will also perform critical validations
    *
    * @param resourceType
    * @param resourceId
    * @param policies
    * @param authDomain
    * @return Future[Resource]
    */
  private def persistResource(resourceType: ResourceType, resourceId: ResourceId, policies: Set[ValidatableAccessPolicy], authDomain: Set[WorkbenchGroupName], parentOpt: Option[FullyQualifiedResourceId], samRequestContext: SamRequestContext) = {
    val accessPolicies = policies.map(constructAccessPolicy(resourceType, resourceId, _, public = false)) // can't set public at create time
    accessPolicyDAO.createResource(Resource(resourceType.name, resourceId, authDomain, accessPolicies = accessPolicies, parent = parentOpt), samRequestContext)
  }

  private def constructAccessPolicy(resourceType: ResourceType, resourceId: ResourceId, validatableAccessPolicy: ValidatableAccessPolicy, public: Boolean) = {
    AccessPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceType.name, resourceId), validatableAccessPolicy.policyName),
      validatableAccessPolicy.emailsToSubjects.values.flatten.toSet,
      generateGroupEmail(),
      validatableAccessPolicy.roles,
      validatableAccessPolicy.actions,
      validatableAccessPolicy.descendantPermissions,
      public
    )
  }

  private def validateCreateResource(resourceType: ResourceType, resourceId: ResourceId, policies: Set[ValidatableAccessPolicy], authDomain: Set[WorkbenchGroupName], userId: WorkbenchUserId, parentOpt: Option[FullyQualifiedResourceId], samRequestContext: SamRequestContext): IO[Seq[ErrorReport]] =
    for {
      resourceIdErrors <- IO.pure(validateUrlSafe(resourceId.value))
      ownerPolicyErrors <- IO.pure(validateOwnerPolicyExists(resourceType, policies, parentOpt))
      policyErrors <- policies.toList.traverse(policy => validatePolicy(resourceType, policy)).map(_.flatten)
      authDomainErrors <- validateAuthDomain(resourceType, authDomain, userId, samRequestContext)
    } yield (resourceIdErrors ++ ownerPolicyErrors ++ policyErrors ++ authDomainErrors).toSeq

  private val validUrlSafePattern = "[-a-zA-Z0-9._~%]+".r

  private def validateUrlSafe(input: String): Option[ErrorReport] = {
    if (!validUrlSafePattern.pattern.matcher(input).matches) {
      Option(ErrorReport(s"Invalid input: $input. Valid characters are alphanumeric characters, periods, tildes, percents, underscores, and dashes. Try url encoding."))
    } else {
      None
    }
  }

  private def validateOwnerPolicyExists(resourceType: ResourceType, policies: Set[ValidatableAccessPolicy], parentOpt: Option[FullyQualifiedResourceId]): Option[ErrorReport] =
    parentOpt match {
      case None =>
        // make sure there is an owner policy if there is no parent
        policies.exists { policy =>
          policy.roles.contains(resourceType.ownerRoleName) && policy.emailsToSubjects.nonEmpty
        } match {
          case true => None
          case false =>
            Option(ErrorReport(s"Cannot create resource without at least 1 policy with ${resourceType.ownerRoleName.value} role and non-empty membership"))
        }

      case Some(_) => None // if a parent exists, the parent's owners are effectively owners of this resource
    }

  private def validateAuthDomain(resourceType: ResourceType, authDomain: Set[WorkbenchGroupName], userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[ErrorReport]] =
    validateAuthDomainPermissions(authDomain, userId, samRequestContext).map { permissionsErrors =>
      val constrainableErrors = validateAuthDomainConstraints(resourceType, authDomain).toSeq
      val errors = constrainableErrors ++ permissionsErrors.flatten
      if (errors.nonEmpty) {
        Option(ErrorReport("Invalid Auth Domain specified", errors))
      } else None
    }

  private def validateAuthDomainPermissions(authDomain: Set[WorkbenchGroupName], userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[List[Option[ErrorReport]]] =
    authDomain.toList.traverse { groupName =>
      val resource = FullyQualifiedResourceId(ManagedGroupService.managedGroupTypeName, ResourceId(groupName.value))
      policyEvaluatorService.hasPermission(resource, ManagedGroupService.useAction, userId, samRequestContext).map {
        case false => Option(ErrorReport(s"You do not have access to $groupName or $groupName does not exist"))
        case _ => None
      }
    }

  private def validateAuthDomainConstraints(resourceType: ResourceType, authDomain: Set[WorkbenchGroupName]): Option[ErrorReport] =
    if (authDomain.nonEmpty && !resourceType.isAuthDomainConstrainable) {
      Option(ErrorReport(s"Auth Domain is not permitted on resource of type: ${resourceType.name}"))
    } else None

  def loadResourceAuthDomain(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupName]] =
    accessPolicyDAO.loadResourceAuthDomain(resource, samRequestContext).flatMap(result => result match {
      case LoadResourceAuthDomainResult.Constrained(authDomain) => IO.pure(authDomain.toList.toSet)
      case LoadResourceAuthDomainResult.NotConstrained => IO.pure(Set.empty)
      case LoadResourceAuthDomainResult.ResourceNotFound => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Resource ${resource} not found")))
    })

  def createPolicy(policyIdentity: FullyQualifiedPolicyId, members: Set[WorkbenchSubject], roles: Set[ResourceRoleName], actions: Set[ResourceAction], descendantPermissions: Set[AccessPolicyDescendantPermissions], samRequestContext: SamRequestContext): IO[AccessPolicy] =
    createPolicy(policyIdentity, members, generateGroupEmail(), roles, actions, descendantPermissions, samRequestContext)

  def createPolicy(policyIdentity: FullyQualifiedPolicyId, members: Set[WorkbenchSubject], email: WorkbenchEmail, roles: Set[ResourceRoleName], actions: Set[ResourceAction], descendantPermissions: Set[AccessPolicyDescendantPermissions], samRequestContext: SamRequestContext): IO[AccessPolicy] =
    accessPolicyDAO.createPolicy(AccessPolicy(policyIdentity, members, email, roles, actions, descendantPermissions, public = false), samRequestContext)

  // IF Resource ID reuse is allowed (as defined by the Resource Type), then we can delete the resource
  // ELSE Resource ID reuse is not allowed, and we enforce this by deleting all policies associated with the Resource,
  //      but not the Resource itself, thereby orphaning the Resource so that it cannot be used or accessed anymore and
  //      preventing a new Resource with the same ID from being created
  // Resources with children cannot be deleted and will throw a 400.
  @throws(classOf[WorkbenchExceptionWithErrorReport]) // Necessary to make Mockito happy
  def deleteResource(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): Future[Unit] =
    for {
      _ <- checkNoChildren(resource, samRequestContext).unsafeToFuture()

      // remove from cloud first so a failure there does not leave sam in a bad state
      _ <- cloudDeletePolicies(resource, samRequestContext)

      _ <- accessPolicyDAO.deleteAllResourcePolicies(resource, samRequestContext).unsafeToFuture()
      _ <- maybeDeleteResource(resource, samRequestContext).unsafeToFuture()
    } yield ()

  /** Check if a resource has any children. If so, then throw a 400. */
  def checkNoChildren(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit] = {
    listResourceChildren(resource, samRequestContext) map { list =>
      if (list.nonEmpty) throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "Cannot delete a resource with children. Delete the children first then try again."))
    }
  }

  // TODO: CA-993 Once we can check if a policy applies to any children, we need to update this to throw if we try
  // to delete any policies that apply to children
  def deletePolicy(policyId: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Unit] = {
    for {
      policyEmailOpt <- directoryDAO.loadSubjectEmail(policyId, samRequestContext)
      policyEmail = policyEmailOpt.getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "policy not found")))
      _ <- IO.fromFuture(IO(cloudExtensions.onGroupDelete(policyEmail)))
      _ <- accessPolicyDAO.deletePolicy(policyId, samRequestContext)
    } yield ()
  }

  def cloudDeletePolicies(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): Future[LazyList[AccessPolicy]] = {
    for {
      policiesToDelete <- accessPolicyDAO.listAccessPolicies(resource, samRequestContext).unsafeToFuture()
      _ <- Future.traverse(policiesToDelete) { policy =>
        cloudExtensions.onGroupDelete(policy.email)
      }
    } yield policiesToDelete
  }

  private def maybeDeleteResource(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit] =
    resourceTypes.get(resource.resourceTypeName) match {
      case Some(resourceType) if resourceType.reuseIds => accessPolicyDAO.deleteResource(resource, samRequestContext)
      case _ =>
        for {
          _ <- accessPolicyDAO.removeAuthDomainFromResource(resource, samRequestContext)
          // orphan the resource so it disappears from the parent
          _ <- accessPolicyDAO.deleteResourceParent(resource, samRequestContext)
        } yield ()
    }

  def listUserResourceRoles(resource: FullyQualifiedResourceId, userInfo: SamUser, samRequestContext: SamRequestContext): Future[Set[ResourceRoleName]] =
    accessPolicyDAO.listUserResourceRoles(resource, userInfo.id, samRequestContext).unsafeToFuture()

  /**
    * Overwrites an existing policy (keyed by resourceType/resourceId/policyName), saves a new one if it doesn't exist yet
    * @param resourceType
    * @param policyName
    * @param resource
    * @param policyMembership
    * @return
    */
  @throws(classOf[WorkbenchExceptionWithErrorReport])
  def overwritePolicy(resourceType: ResourceType, policyName: AccessPolicyName, resource: FullyQualifiedResourceId, policyMembership: AccessPolicyMembership, samRequestContext: SamRequestContext): IO[AccessPolicy] = {
    for {
      policy <- makeCreatablePolicy(policyName, policyMembership, samRequestContext)
      _ <- validatePolicy(resourceType, policy).map {
        case Some(errorReport) =>
          throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "You have specified an invalid policy", errorReport))
        case None =>
      }
      overwrittenPolicy <- createOrUpdatePolicy(FullyQualifiedPolicyId(resource, policyName), policy, samRequestContext)
    } yield {
      overwrittenPolicy
    }
  }

  /**
    * Overwrites an existing policy's membership (keyed by resourceType/resourceId/policyName) if it exists
    * @param policyId
    * @param membersList
    * @return
    */
  def overwritePolicyMembers(policyId: FullyQualifiedPolicyId, membersList: Set[WorkbenchEmail], samRequestContext: SamRequestContext): IO[Unit] =
    mapEmailsToSubjects(membersList, samRequestContext).flatMap { emailsToSubjects =>
      validateMemberEmails(emailsToSubjects) match {
        case Some(error) => IO.raiseError(new WorkbenchExceptionWithErrorReport(error.copy(statusCode = Option(StatusCodes.BadRequest))))
        case None =>
          val newMembers = emailsToSubjects.values.flatten.toSet
          accessPolicyDAO.loadPolicy(policyId, samRequestContext).flatMap {
            case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"policy $policyId does not exist")))
            case Some(existingPolicy) if existingPolicy.members == newMembers => IO.unit
            case _ => for {
              _ <- accessPolicyDAO.overwritePolicyMembers(policyId, newMembers, samRequestContext)
              _ <- fireGroupUpdateNotification(policyId, samRequestContext)
            } yield ()
          }

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
  private def createOrUpdatePolicy(policyIdentity: FullyQualifiedPolicyId, policy: ValidatableAccessPolicy, samRequestContext: SamRequestContext): IO[AccessPolicy] = {
    val workbenchSubjects = policy.emailsToSubjects.values.flatten.toSet
    accessPolicyDAO.loadPolicy(policyIdentity, samRequestContext).flatMap {
      case None => createPolicy(policyIdentity, workbenchSubjects, generateGroupEmail(), policy.roles, policy.actions, policy.descendantPermissions, samRequestContext)
      case Some(existingAccessPolicy) =>
        val newAccessPolicy = AccessPolicy(policyIdentity, workbenchSubjects, existingAccessPolicy.email, policy.roles, policy.actions, policy.descendantPermissions, existingAccessPolicy.public)
        if (newAccessPolicy == existingAccessPolicy) {
          // short cut if access policy is unchanged
          IO.pure(newAccessPolicy)
        } else {
          for {
            result <- accessPolicyDAO.overwritePolicy(newAccessPolicy, samRequestContext)
            _ <- fireGroupUpdateNotification(policyIdentity, samRequestContext)
          } yield {
            result
          }
        }
    }
  }

  private def mapEmailsToSubjects(workbenchEmails: Set[WorkbenchEmail], samRequestContext: SamRequestContext): IO[Map[WorkbenchEmail, Option[WorkbenchSubject]]] = {
    val eventualSubjects = workbenchEmails.map { workbenchEmail =>
      directoryDAO.loadSubjectFromEmail(workbenchEmail, samRequestContext).map(workbenchEmail -> _)
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
  private[service] def validatePolicy(resourceType: ResourceType, policy: ValidatableAccessPolicy): IO[Option[ErrorReport]] = {
    for {
      descendantPermissionsErrors <- validateDescendantPermissions(policy.descendantPermissions)
    } yield {
      val validationErrors =
        validateMemberEmails(policy.emailsToSubjects) ++
          validateActions(resourceType, policy.actions) ++
          validateRoles(resourceType, policy.roles) ++
          validateUrlSafe(policy.policyName.value) ++
          descendantPermissionsErrors

      if (validationErrors.nonEmpty) {
        Some(ErrorReport("You have specified an invalid policy", validationErrors.toSeq))
      } else None
    }
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

  private[service] def validateRoles(resourceType: ResourceType, roles: Set[ResourceRoleName]) = {
    val invalidRoles = roles -- resourceType.roles.map(_.roleName)
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

  private[service] def validateActions(resourceType: ResourceType, actions: Set[ResourceAction]) = {
    val invalidActions = actions.filter(a => !resourceType.actionPatterns.exists(_.matches(a)))
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

  private[service] def validateDescendantPermissions(descendantPermissionsSet: Set[AccessPolicyDescendantPermissions]): IO[Seq[ErrorReport]] = {
    val validationErrors = descendantPermissionsSet.toList.traverse { descendantPermissions =>
      for {
        maybeDescendantResourceType <- getResourceType(descendantPermissions.resourceType)
      } yield {
        maybeDescendantResourceType match {
          case None => Seq(ErrorReport(s"Descendant resource type ${descendantPermissions.resourceType.value} does not exist."))
          case Some(descendantResourceType) =>
            validateActions(descendantResourceType, descendantPermissions.actions) ++
            validateRoles(descendantResourceType, descendantPermissions.roles)
        }
      }
    }

    validationErrors.map(_.flatten)
  }

  private def fireGroupUpdateNotification(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Unit] =
    IO.fromFuture(IO(cloudExtensions.onGroupUpdate(Seq(groupId), samRequestContext))).attempt.flatMap {
      case Left(regrets) => IO(logger.error(s"error calling cloudExtensions.onGroupUpdate for $groupId", regrets))
      case Right(_) => IO.unit
    }

  def addSubjectToPolicy(policyIdentity: FullyQualifiedPolicyId, subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] = {
    subject match {
      case subject: FullyQualifiedPolicyId if policyIdentity.resource.resourceTypeName.equals(ManagedGroupService.managedGroupTypeName) =>
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"Access policies cannot be added to managed groups.")))
      case _ =>
        directoryDAO.addGroupMember(policyIdentity, subject, samRequestContext)
          .flatMap(fireGroupUpdateNotificationIfGroupChanged(policyIdentity, samRequestContext))
    }
  }

  def removeSubjectFromPolicy(policyIdentity: FullyQualifiedPolicyId, subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDAO.removeGroupMember(policyIdentity, subject, samRequestContext)
      .flatMap(fireGroupUpdateNotificationIfGroupChanged(policyIdentity, samRequestContext))

  private def fireGroupUpdateNotificationIfGroupChanged(policyIdentity: FullyQualifiedPolicyId, samRequestContext: SamRequestContext)(groupChanged: Boolean) = {
    val maybeFireNotification = if (groupChanged) {
      fireGroupUpdateNotification(policyIdentity, samRequestContext)
    } else {
      IO.unit
    }
    maybeFireNotification.map(_ => groupChanged)
  }

  def listResourcePolicies(resource: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[LazyList[AccessPolicyResponseEntry]] =
    accessPolicyDAO.listAccessPolicyMemberships(resource, samRequestContext).map { policiesWithMembership =>
      policiesWithMembership.map { policyWithMembership =>
        AccessPolicyResponseEntry(policyWithMembership.policyName, policyWithMembership.membership, policyWithMembership.email)
      }
    }

  def loadResourcePolicy(policyIdentity: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Option[AccessPolicyMembership]] =
    accessPolicyDAO.loadPolicyMembership(policyIdentity, samRequestContext)

  private def makeValidatablePolicies(policies: Map[AccessPolicyName, AccessPolicyMembership], samRequestContext: SamRequestContext): IO[Set[ValidatableAccessPolicy]] =
    policies.toList.traverse {
      case (accessPolicyName, accessPolicyMembership) => makeCreatablePolicy(accessPolicyName, accessPolicyMembership, samRequestContext)
    }.map(_.toSet)

  private def makeCreatablePolicy(accessPolicyName: AccessPolicyName, accessPolicyMembership: AccessPolicyMembership, samRequestContext: SamRequestContext): IO[ValidatableAccessPolicy] =
    mapEmailsToSubjects(accessPolicyMembership.memberEmails, samRequestContext).map { emailsToSubjects =>
      ValidatableAccessPolicy(accessPolicyName, emailsToSubjects, accessPolicyMembership.roles, accessPolicyMembership.actions, accessPolicyMembership.getDescendantPermissions)
    }

  private def generateGroupEmail() = WorkbenchEmail(s"policy-${UUID.randomUUID}@$emailDomain")

  def isPublic(resourceAndPolicyName: FullyQualifiedPolicyId, samRequestContext: SamRequestContext): IO[Boolean] =
    accessPolicyDAO.loadPolicy(resourceAndPolicyName, samRequestContext).flatMap {
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
  def setPublic(policyId: FullyQualifiedPolicyId, public: Boolean, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      authDomain <- accessPolicyDAO.loadResourceAuthDomain(policyId.resource, samRequestContext)
      _ <- authDomain match {
        case LoadResourceAuthDomainResult.ResourceNotFound => IO.raiseError(
          new WorkbenchExceptionWithErrorReport(
            ErrorReport(StatusCodes.BadRequest, s"ResourceId ${policyId.resource} not found.")))
        case LoadResourceAuthDomainResult.Constrained(_) =>
          // resources with auth domains logically can't have public policies but also technically allowing them poses a problem
          // because the logic for public resources is different. However, sharing with the auth domain should have the
          // exact same effect as making a policy public: anyone in the auth domain can access.
          if (public)
          IO.raiseError(
            new WorkbenchExceptionWithErrorReport(
              ErrorReport(StatusCodes.BadRequest, "Cannot make auth domain protected resources public. Share directly with auth domain groups instead.")))
          else IO.unit
        case LoadResourceAuthDomainResult.NotConstrained =>
          accessPolicyDAO.setPolicyIsPublic(policyId, public, samRequestContext) *> fireGroupUpdateNotification(policyId, samRequestContext)
      }
    } yield ()

  def listAllFlattenedResourceUsers(resourceId: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Set[UserIdInfo]] =
    for {
      accessPolicies <- accessPolicyDAO.listAccessPolicies(resourceId, samRequestContext)
      members <- accessPolicies.toList.parTraverse(accessPolicy => accessPolicyDAO.listFlattenedPolicyMembers(accessPolicy.id, samRequestContext))
      workbenchUsers = members.flatten.toSet
    } yield {
      workbenchUsers.map(_.toUserIdInfo)
    }

  @throws(classOf[WorkbenchExceptionWithErrorReport]) // Necessary to make Mockito happy
  def getResourceParent(resourceId: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Option[FullyQualifiedResourceId]] = {
    accessPolicyDAO.getResourceParent(resourceId, samRequestContext)
  }

  /** In this iteration of hierarchical resources, we do not allow child resources to be in an auth domain because it
    * would introduce additional complications when keeping Sam policies with their Google Groups. For more details,
    * see https://docs.google.com/document/d/10qGxsV9BeM6-N_Zk27_JIayE509B8LUQBGiGrqB0taY/edit#heading=h.dxz6xjtnz9la */
  def setResourceParent(childResource: FullyQualifiedResourceId, parentResource: FullyQualifiedResourceId,  samRequestContext: SamRequestContext): IO[Unit] = {
    for {
      authDomain <- accessPolicyDAO.loadResourceAuthDomain(childResource, samRequestContext)
      _ <- authDomain match {
        case LoadResourceAuthDomainResult.NotConstrained =>
          accessPolicyDAO.setResourceParent(childResource, parentResource, samRequestContext)
        case LoadResourceAuthDomainResult.Constrained(_) => IO.raiseError(
          new WorkbenchExceptionWithErrorReport(
            ErrorReport(StatusCodes.BadRequest, "Cannot set the parent for a constrained resource")
          )
        )
        case LoadResourceAuthDomainResult.ResourceNotFound => IO.raiseError(
          new WorkbenchExceptionWithErrorReport(
            ErrorReport(StatusCodes.NotFound, "Resource not found")
          )
        )
      }
    } yield ()
  }

  def deleteResourceParent(resourceId: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Boolean] = {
    accessPolicyDAO.deleteResourceParent(resourceId, samRequestContext)
  }

  def listResourceChildren(resourceId: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Set[FullyQualifiedResourceId]] = {
    accessPolicyDAO.listResourceChildren(resourceId, samRequestContext)
  }
}
