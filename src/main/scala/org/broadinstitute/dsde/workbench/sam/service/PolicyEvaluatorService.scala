package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.{AccessPolicyDAO, LoadResourceAuthDomainResult}
import org.broadinstitute.dsde.workbench.sam.util.OpenCensusIOUtils._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.ExecutionContext

class PolicyEvaluatorService(
    private val emailDomain: String,
    private val resourceTypes: Map[ResourceTypeName, ResourceType],
    private val accessPolicyDAO: AccessPolicyDAO,
    private val directoryDAO: DirectoryDAO )(implicit val executionContext: ExecutionContext)
    extends LazyLogging {
  def initPolicy(): IO[Unit] = {
    val policyName = AccessPolicyName("admin-notifier-set-public")
    accessPolicyDAO
      .createPolicy(
        AccessPolicy(
          FullyQualifiedPolicyId(FullyQualifiedResourceId(SamResourceTypes.resourceTypeAdminName, ResourceId("managed-group")), policyName),
          Set.empty,
          WorkbenchEmail(s"dummy@$emailDomain"),
          Set.empty,
          Set(SamResourceActions.setPublicPolicy(ManagedGroupService.adminNotifierPolicyName)),
          true
        ), SamRequestContext(None))
      .void
      .recoverWith {
        case duplicateException: WorkbenchExceptionWithErrorReport if duplicateException.errorReport.statusCode.contains(StatusCodes.Conflict) =>
          IO(logger.debug(s"$policyName has already been created"))
      }
  }

  def hasPermission(resource: FullyQualifiedResourceId, action: ResourceAction, userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Boolean] = traceIOWithContext("hasPermission", samRequestContext)(samRequestContext => {
    def checkPermission(force: Boolean) =
      listUserResourceActions(resource, userId, force, samRequestContext).map { _.contains(action) }

    // this is optimized for the case where the user has permission since that is the usual case
    // if the first attempt shows the user does not have permission, force a second attempt
    for {
      attempt1 <- traceIOWithContext("checkWithCache", samRequestContext)(_ => checkPermission(force = false))
      attempt2 <- if (attempt1) IO.pure(attempt1) else traceIOWithContext("checkWithoutCache", samRequestContext)(_ => checkPermission(force = true))
    } yield {
      attempt2
    }
  })

  /** Checks if user have permission by providing user email address. */
  def hasPermissionByUserEmail(resource: FullyQualifiedResourceId, action: ResourceAction, userEmail: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Boolean] = traceIOWithContext("hasPermission", samRequestContext)(samRequestContext => {
    for {
      subjectOpt <- directoryDAO.loadSubjectFromEmail(userEmail, samRequestContext)
      res <- subjectOpt match {
        case Some(userId: WorkbenchUserId) => hasPermission(resource, action, userId, samRequestContext)
        case _ => IO.pure(false)
      }
    } yield res
  })

  /**
    * Lists all the actions a user has on the specified resource
    *
    * @param resource
    * @param userId
    * @param force true to ignore any caching
    * @return
    */
  def listUserResourceActions(resource: FullyQualifiedResourceId, userId: WorkbenchUserId, force: Boolean = false, samRequestContext: SamRequestContext): IO[Set[ResourceAction]] = {
    def allActions(policy: AccessPolicyWithoutMembers, resourceType: ResourceType): Set[ResourceAction] = {
      val roleActions = policy.roles.flatMap { role =>
        resourceType.roles.filter(_.roleName == role).flatMap(_.actions)
      }
      policy.actions ++ roleActions
    }

    for {
      _ <- if (force) accessPolicyDAO.evictIsMemberOfCache(userId) else IO.unit
      rt <- IO.fromEither[ResourceType](
        resourceTypes.get(resource.resourceTypeName).toRight(new WorkbenchException(s"missing configuration for resourceType ${resource.resourceTypeName}")))
      isConstrainable = rt.isAuthDomainConstrainable

      policiesForResource <- listResourceAccessPoliciesForUser(resource, userId, samRequestContext)
      allPolicyActions = policiesForResource.flatMap(p => allActions(p, rt))
      res <- if (isConstrainable) {
        for {
          authDomainsResult <- accessPolicyDAO.loadResourceAuthDomain(resource, samRequestContext)
          policyActions <- authDomainsResult match {
            case LoadResourceAuthDomainResult.Constrained(authDomains) =>
              listUserManagedGroups(userId, samRequestContext).map{
                groupsUserIsMemberOf =>
                  val isUserMemberOfAllAuthDomains = authDomains.toList.toSet.subsetOf(groupsUserIsMemberOf)
                  if (isUserMemberOfAllAuthDomains) {
                    allPolicyActions
                  } else {
                    val constrainableActions = rt.actionPatterns.filter(_.authDomainConstrainable)
                    allPolicyActions.filterNot(x => constrainableActions.exists(_.matches(x)))
                  }
              }
            case LoadResourceAuthDomainResult.NotConstrained | LoadResourceAuthDomainResult.ResourceNotFound =>
              allPolicyActions.pure[IO]
          }
        } yield policyActions
      } else allPolicyActions.pure[IO]
    } yield res
  }

  def listUserAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[UserPolicyResponse]] =
    for {
      rt <- IO.fromEither(
        resourceTypes
          .get(resourceTypeName)
          .toRight(new WorkbenchException(s"missing configration for resourceType ${resourceTypeName}")))
      isConstrained = rt.isAuthDomainConstrainable
      ridAndPolicyName <- accessPolicyDAO.listAccessPolicies(resourceTypeName, userId, samRequestContext) // List all policies of a given resourceType the user is a member of
      rids = ridAndPolicyName.map(_.resourceId)

      resources <- if (isConstrained) accessPolicyDAO.listResourcesWithAuthdomains(resourceTypeName, rids, samRequestContext)
      else IO.pure(Set.empty)
      authDomainMap = resources.map(x => x.resourceId -> x.authDomain).toMap

      userManagedGroups <- if (isConstrained) listUserManagedGroups(userId, samRequestContext) else IO.pure(Set.empty[WorkbenchGroupName])

      results = ridAndPolicyName.map { rnp =>
        if (isConstrained) {
          authDomainMap.get(rnp.resourceId) match {
            case Some(resourceAuthDomains) =>
              val userNotMemberOf = resourceAuthDomains -- userManagedGroups
              Some(UserPolicyResponse(rnp.resourceId, rnp.accessPolicyName, resourceAuthDomains, userNotMemberOf, false))
            case None =>
              logger.error(s"ldap has corrupted data. ${rnp.resourceId} should have auth domains defined")
              none[UserPolicyResponse]
          }
        } else UserPolicyResponse(rnp.resourceId, rnp.accessPolicyName, Set.empty, Set.empty, false).some
      }
      publicPolicies <- accessPolicyDAO.listPublicAccessPolicies(resourceTypeName, samRequestContext)
    } yield results.flatten ++ publicPolicies.map(p => UserPolicyResponse(p.resourceId, p.accessPolicyName, Set.empty, Set.empty, public = true))

  def listUserManagedGroupsWithRole(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[ManagedGroupAndRole]] =
    for {
      ripns <- accessPolicyDAO.listAccessPolicies(ManagedGroupService.managedGroupTypeName, userId, samRequestContext)
    } yield {
      ripns
        .filter(ripn => ManagedGroupService.userMembershipPolicyNames.contains(ripn.accessPolicyName))
        .map(p => ManagedGroupAndRole(WorkbenchGroupName(p.resourceId.value), ManagedGroupService.getRoleName(p.accessPolicyName.value)))
    }

  def listUserManagedGroups(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupName]] =
    for {
      policies <- listUserManagedGroupsWithRole(userId, samRequestContext)
    } yield {
      policies.map(_.groupName)
    }

  def listResourceAccessPoliciesForUser(resource: FullyQualifiedResourceId, userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[AccessPolicyWithoutMembers]] =
    for {
      policies <- traceIOWithContext("listAccessPoliciesForUser", samRequestContext)(samRequestContext => accessPolicyDAO.listAccessPoliciesForUser(resource, userId, samRequestContext))
      publicPolicies <- traceIOWithContext("listPublicAccessPolicies", samRequestContext)(samRequestContext => accessPolicyDAO.listPublicAccessPolicies(resource, samRequestContext))
    } yield policies ++ publicPolicies
}

object PolicyEvaluatorService {
  def apply(emailDomain: String, resourceTypes: Map[ResourceTypeName, ResourceType], accessPolicyDAO: AccessPolicyDAO, directoryDAO: DirectoryDAO)(
      implicit executionContext: ExecutionContext): PolicyEvaluatorService =
    new PolicyEvaluatorService(emailDomain, resourceTypes, accessPolicyDAO, directoryDAO)
}
