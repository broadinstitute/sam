package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import com.unboundid.ldap.sdk.{LDAPException, ResultCode}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO

import scala.concurrent.ExecutionContext

class PolicyEvaluatorService(
    private val emailDomain: String,
    private val resourceTypes: Map[ResourceTypeName, ResourceType],
    private val accessPolicyDAO: AccessPolicyDAO)(implicit val executionContext: ExecutionContext)
    extends LazyLogging {
  def initPolicy(): IO[Unit] = {
    val policyName = AccessPolicyName("admin-notifier-set-public")
    accessPolicyDAO.createPolicy(AccessPolicy(
      FullyQualifiedPolicyId(FullyQualifiedResourceId(SamResourceTypes.resourceTypeAdminName, ResourceId("managed-group")), policyName),
      Set.empty,
      WorkbenchEmail(s"dummy@$emailDomain"),
      Set.empty,
      Set(SamResourceActions.setPublicPolicy(ManagedGroupService.adminNotifierPolicyName)),
      true
    )).void.recoverWith{
      case ldape: LDAPException if ldape.getResultCode == ResultCode.ENTRY_ALREADY_EXISTS =>
        IO(logger.debug(s"$policyName has already been created"))
    }
  }

  def hasPermission(resource: FullyQualifiedResourceId, action: ResourceAction, userId: WorkbenchUserId): IO[Boolean] =
    listUserResourceActions(resource, userId).map { _.contains(action) }

  def listUserResourceActions(resource: FullyQualifiedResourceId, userId: WorkbenchUserId): IO[Set[ResourceAction]] = {
    def allActions(policy: AccessPolicy, resourceType: ResourceType): Set[ResourceAction] = {
      val roleActions = policy.roles.flatMap{
        role =>
          resourceType.roles.filter(_.roleName == role).flatMap(_.actions)
      }
      policy.actions ++ roleActions
    }

    for{
      rt <- IO.fromEither[ResourceType](resourceTypes.get(resource.resourceTypeName).toRight(new WorkbenchException(s"missing configuration for resourceType ${resource.resourceTypeName}")))
      isConstrained = rt.isAuthDomainConstrainable

      policiesForResource <- listResourceAccessPoliciesForUser(resource, userId)
      allPolicyActions = policiesForResource.flatMap(p => allActions(p, rt))
      res <- if(isConstrained) {
        for{
          authDomains <- accessPolicyDAO.loadResourceAuthDomain(resource)
          groupsUserIsMemberOf <- listUserManagedGroups(userId)
        } yield {
          val isUserMemberOfAllAuthDomains = authDomains.subsetOf(groupsUserIsMemberOf)
          if(isUserMemberOfAllAuthDomains){
            allPolicyActions
          } else {
            val constrainableActions = rt.actionPatterns.filter(_.authDomainConstrainable)
            allPolicyActions.filterNot(x => constrainableActions.exists(_.matches(x)))
          }
        }
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

      resources <- if (isConstrained) accessPolicyDAO.listResourceWithAuthdomains(resourceTypeName, rids)
      else IO.pure(Set.empty)
      authDomainMap = resources.map(x => x.resourceId -> x.authDomain).toMap

      userMangedGroups <- if (isConstrained) listUserManagedGroups(userId) else IO.pure(Set.empty[WorkbenchGroupName])

      results = ridAndPolicyName.map { rnp =>
        if (isConstrained) {
          authDomainMap.get(rnp.resourceId) match {
            case Some(resourceAuthDomains) =>
              val userNotMemberOf = resourceAuthDomains -- userMangedGroups
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
      ripns.filter(ripn => ManagedGroupService.userMembershipPolicyNames.contains(ripn.accessPolicyName))
        .map(p => ManagedGroupAndRole(WorkbenchGroupName(p.resourceId.value), ManagedGroupService.getRoleName(p.accessPolicyName.value)))
    }

  def listUserManagedGroups(userId: WorkbenchUserId): IO[Set[WorkbenchGroupName]] = {
    for {
      policies <- listUserManagedGroupsWithRole(userId)
    } yield {
      policies.map(_.groupName)
    }
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
