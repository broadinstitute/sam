package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.{AccessPolicyDAO, LoadResourceAuthDomainResult}

import scala.concurrent.ExecutionContext

class PolicyEvaluatorService(
    private val emailDomain: String,
    private val resourceTypes: Map[ResourceTypeName, ResourceType],
    private val accessPolicyDAO: AccessPolicyDAO)(implicit val executionContext: ExecutionContext)
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
        ))
      .void
      .recoverWith {
        case duplicateException: WorkbenchExceptionWithErrorReport if duplicateException.errorReport.statusCode.contains(StatusCodes.Conflict) =>
          IO(logger.debug(s"$policyName has already been created"))
      }
  }

  def hasPermission(resource: FullyQualifiedResourceId, action: ResourceAction, userId: WorkbenchUserId): IO[Boolean] = {
    // first attempt the shallow check and fallback to the full check if it returns false
    for {
      attempt1 <- hasPermissionShallowCheck(resource, action, userId)
      attempt2 <- if (attempt1) IO.pure(attempt1) else hasPermissionFullCheck(resource, action, userId)
    } yield {
      attempt2
    }
  }

  def hasPermissionFullCheck(resource: FullyQualifiedResourceId, action: ResourceAction, userId: WorkbenchUserId): IO[Boolean] = {
    def checkPermission(force: Boolean) =
      listUserResourceActions(resource, userId, force).map { _.contains(action) }

    // this is optimized for the case where the user has permission since that is the usual case
    // if the first attempt shows the user does not have permission, force a second attempt
    for {
      attempt1 <- checkPermission(force = false)
      attempt2 <- if (attempt1) IO.pure(attempt1) else checkPermission(force = true)
    } yield {
      attempt2
    }
  }

  /**
    * Check if the user has the action on the resource.  A true result means they do, and can be trusted.  A false result only means that
    * the shallow check did not find that to be true, but a more exhaustive check might.
    *
    * In many cases users are direct members of policies, and it is very fast to query direct members of a policy (as opposed to
    * calling isMemberOf for a user), this method leverages that to do a fast check for presence
    */
  def hasPermissionShallowCheck(resource: FullyQualifiedResourceId, action: ResourceAction, userId: WorkbenchUserId): IO[Boolean] = {

    for {
      res <- resourceTypes.get(resource.resourceTypeName).traverse {
        rt =>

          // we can only use this approach if the resource type isn't auth-domain constrainable
          if (!rt.isAuthDomainConstrainable) {

            // compute the list of roles that contain the action requested
            val roleNamesWithAction = rt.roles.filter(_.actions.contains(action)).map(_.roleName)

            for {
              // get the policies for this resource and filter to ones that contain the roles with the action, or the action directly
              policies <- accessPolicyDAO.listAccessPolicies(resource).map(_.toList.filter(p => p.roles.intersect(roleNamesWithAction).nonEmpty || p.actions.contains(action)))
              members = policies.flatMap(_.members)
            } yield members.contains(userId)
          } else IO.pure(false)
      }
    } yield res.getOrElse(false)

  }

  /**
    * Lists all the actions a user has on the specified resource
    *
    * @param resource
    * @param userId
    * @param force true to ignore any caching
    * @return
    */
  def listUserResourceActions(resource: FullyQualifiedResourceId, userId: WorkbenchUserId, force: Boolean = false): IO[Set[ResourceAction]] = {
    def allActions(policy: AccessPolicy, resourceType: ResourceType): Set[ResourceAction] = {
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

      policiesForResource <- listResourceAccessPoliciesForUser(resource, userId)
      allPolicyActions = policiesForResource.flatMap(p => allActions(p, rt))
      res <- if (isConstrainable) {
        for {
          authDomainsResult <- accessPolicyDAO.loadResourceAuthDomain(resource)
          policyActions <- authDomainsResult match {
            case LoadResourceAuthDomainResult.Constrained(authDomains) =>
              listUserManagedGroups(userId).map{
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

  def listUserAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId): IO[Set[UserPolicyResponse]] =
    for {
      rt <- IO.fromEither(
        resourceTypes
          .get(resourceTypeName)
          .toRight(new WorkbenchException(s"missing configration for resourceType ${resourceTypeName}")))
      isConstrained = rt.isAuthDomainConstrainable
      ridAndPolicyName <- accessPolicyDAO.listAccessPolicies(resourceTypeName, userId) // List all policies of a given resourceType the user is a member of
      rids = ridAndPolicyName.map(_.resourceId)

      resources <- if (isConstrained) accessPolicyDAO.listResourcesWithAuthdomains(resourceTypeName, rids)
      else IO.pure(Set.empty)
      authDomainMap = resources.map(x => x.resourceId -> x.authDomain).toMap

      userManagedGroups <- if (isConstrained) listUserManagedGroups(userId) else IO.pure(Set.empty[WorkbenchGroupName])

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
      publicPolicies <- accessPolicyDAO.listPublicAccessPolicies(resourceTypeName)
    } yield results.flatten ++ publicPolicies.map(p => UserPolicyResponse(p.resourceId, p.accessPolicyName, Set.empty, Set.empty, public = true))

  def listUserManagedGroupsWithRole(userId: WorkbenchUserId): IO[Set[ManagedGroupAndRole]] =
    for {
      ripns <- accessPolicyDAO.listAccessPolicies(ManagedGroupService.managedGroupTypeName, userId)
    } yield {
      ripns
        .filter(ripn => ManagedGroupService.userMembershipPolicyNames.contains(ripn.accessPolicyName))
        .map(p => ManagedGroupAndRole(WorkbenchGroupName(p.resourceId.value), ManagedGroupService.getRoleName(p.accessPolicyName.value)))
    }

  def listUserManagedGroups(userId: WorkbenchUserId): IO[Set[WorkbenchGroupName]] =
    for {
      policies <- listUserManagedGroupsWithRole(userId)
    } yield {
      policies.map(_.groupName)
    }

  def listResourceAccessPoliciesForUser(resource: FullyQualifiedResourceId, userId: WorkbenchUserId): IO[Set[AccessPolicy]] =
    for {
      policies <- accessPolicyDAO.listAccessPoliciesForUser(resource, userId)
      publicPolicies <- accessPolicyDAO.listPublicAccessPolicies(resource)
    } yield policies ++ publicPolicies
}

object PolicyEvaluatorService {
  def apply(emailDomain: String, resourceTypes: Map[ResourceTypeName, ResourceType], accessPolicyDAO: AccessPolicyDAO)(
      implicit executionContext: ExecutionContext): PolicyEvaluatorService =
    new PolicyEvaluatorService(emailDomain, resourceTypes, accessPolicyDAO)
}
