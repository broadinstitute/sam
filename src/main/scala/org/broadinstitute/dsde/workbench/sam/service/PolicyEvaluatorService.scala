package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import com.unboundid.ldap.sdk.{LDAPException, ResultCode}
import io.opencensus.trace.Span
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.{AccessPolicyDAO, LoadResourceAuthDomainResult}

import scala.concurrent.ExecutionContext
import org.broadinstitute.dsde.workbench.sam.util.OpenCensusIOUtils._

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
        case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
          IO(logger.debug(s"$policyName has already been created"))
      }
  }

  // TODO: the way this is build... it `should` be ok to enable as the default.... but MUST REVIEW!!!
  def hasPermission(resource: FullyQualifiedResourceId, action: ResourceAction, userId: WorkbenchUserId, attemptShortCircuit: Boolean = true): IO[Boolean] = {
    def checkPermission(force: Boolean) =
      listUserResourceActions(resource, userId, force).map { _.contains(action) }

    if (attemptShortCircuit) {
      for {
        attempt1 <- hasPermissionShortCircuit(resource, action, userId)
        attempt2 <- if (attempt1) IO.pure(attempt1) else hasPermission(resource, action, userId, false)
      } yield {
        attempt2
      }
    } else {
      // this is optimized for the case where the user has permission since that is the usual case
      // if the first attempt shows the user does not have permission, force a second attempt
      for {
        attempt1 <- checkPermission(force = false)
        attempt2 <- if (attempt1) IO.pure(attempt1) else checkPermission(force = true)
      } yield {
        attempt2
      }
    }
  }

  // attempt to succeed quickly for the case where the user is a direct member of a group that with the supplied action either
  // via a role or directly on the policy
  private def hasPermissionShortCircuit(resource: FullyQualifiedResourceId, action: ResourceAction, userId: WorkbenchUserId): IO[Boolean] = {

    // TODO: is this a valid thing?
    // if this is auth domain constrainable (or is unknown), fallback
    resourceTypes.get(resource.resourceTypeName) match {
      case Some(x) if !x.isAuthDomainConstrainable =>
        // nothing
      case _ =>
        return IO { false }
    }

    // compute the list of roles that contain the action requested
    val roleNamesWithAction = resourceTypes.get(resource.resourceTypeName).get.roles.filter(_.actions.contains(action)).map(_.roleName)

    // get the policies for this resource

    // NOTE: validate direction action logic
    // TODO: question... is there such a thing as an 'excluded' action on a policy?
    val policies: IO[List[AccessPolicy]] =
      accessPolicyDAO.listAccessPolicies(resource).map(_.toList.filter( p => p.roles.intersect(roleNamesWithAction).nonEmpty || p.actions.contains( action ) ))

    // get all the members of all the policies
    val members = policies.map(l => l.flatMap(_.members))

    // NOTE: if we wanted to do our own group recursion, we could do it here...
    members.map( _.contains(userId) )
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

    traceIO("listUserResourceActions") ( span =>
      for {
      _ <- if (force) accessPolicyDAO.evictIsMemberOfCache(userId) else IO.unit
      rt <- IO.fromEither[ResourceType](
        resourceTypes.get(resource.resourceTypeName).toRight(new WorkbenchException(s"missing configuration for resourceType ${resource.resourceTypeName}")))
      isConstrainable = rt.isAuthDomainConstrainable

      policiesForResource <- traceIOWithParent("listResourceAccessPoliciesForUser", span)(s2 => listResourceAccessPoliciesForUser(resource, userId, s2))
      allPolicyActions = policiesForResource.flatMap(p => allActions(p, rt))
      res <- if (isConstrainable) {
        for {
          authDomainsResult <- traceIOWithParent("loadResourceAuthDomain", span)(_ => accessPolicyDAO.loadResourceAuthDomain(resource))
          policyActions <- authDomainsResult match {
            case LoadResourceAuthDomainResult.Constrained(authDomains) =>
              traceIOWithParent("listUserManagedGroups", span)(_ => listUserManagedGroups(userId)).map{
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
    } yield res)
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

  def listResourceAccessPoliciesForUser(resource: FullyQualifiedResourceId, userId: WorkbenchUserId, parentSpan: Span  = null): IO[Set[AccessPolicy]] =
    for {
      policies <- traceIOWithParent("listAccessPoliciesForUser", parentSpan)(s2 => accessPolicyDAO.listAccessPoliciesForUser(resource, userId, s2))
      publicPolicies <- traceIOWithParent("listPublicAccessPolicies", parentSpan)(_ => accessPolicyDAO.listPublicAccessPolicies(resource))
    } yield policies ++ publicPolicies
}

object PolicyEvaluatorService {
  def apply(emailDomain: String, resourceTypes: Map[ResourceTypeName, ResourceType], accessPolicyDAO: AccessPolicyDAO)(
      implicit executionContext: ExecutionContext): PolicyEvaluatorService =
    new PolicyEvaluatorService(emailDomain, resourceTypes, accessPolicyDAO)
}
