package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.AccessPolicyDAO

import scala.concurrent.{ExecutionContext, Future}

class PolicyEvaluatorService(
    private val resourceTypes: Map[ResourceTypeName, ResourceType],
    private val accessPolicyDAO: AccessPolicyDAO)(implicit val executionContext: ExecutionContext)
    extends LazyLogging {
  def hasPermission(resource: Resource, action: ResourceAction, userId: WorkbenchUserId): Future[Boolean] =
    listUserResourceActions(resource, userId).map { _.contains(action) }

  def listUserResourceActions(resource: Resource, userId: WorkbenchUserId): Future[Set[ResourceAction]] = {
    def roleActions(resourceTypeOption: Option[ResourceType], resourceRoleName: ResourceRoleName) = {
      val maybeActions = for {
        resourceType <- resourceTypeOption
        role <- resourceType.roles.find(_.roleName == resourceRoleName)
      } yield {
        role.actions
      }
      maybeActions.getOrElse(Set.empty)
    }

    val resourceType = resourceTypes.get(resource.resourceTypeName)
    for {
      policies <- accessPolicyDAO.listAccessPoliciesForUser(resource, userId)
    } yield {
      policies.flatMap(policy => policy.actions ++ policy.roles.flatMap(roleActions(resourceType, _)))
    }
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

      allAuthDomain <- if (isConstrained) listUserManagedGroups(userId) else IO.pure(Set.empty[ResourceIdAndPolicyName])

      allAuthDomainResourcesUserIsMemberOf = allAuthDomain.filter(x =>
        ManagedGroupService.userMembershipPolicyNames.contains(x.accessPolicyName))
      results = ridAndPolicyName.map { rnp =>
        if (isConstrained) {
          authDomainMap.get(rnp.resourceId) match {
            case Some(authDomains) =>
              val userNotMemberOf = authDomains.filterNot(x =>
                allAuthDomainResourcesUserIsMemberOf.map(_.resourceId).contains(ResourceId(x.value)))
              Some(UserPolicyResponse(rnp.resourceId, rnp.accessPolicyName, authDomains, userNotMemberOf))
            case None =>
              logger.error(s"ldap has corrupted data. ${rnp.resourceId} should have auth domains defined")
              none[UserPolicyResponse]
          }
        } else UserPolicyResponse(rnp.resourceId, rnp.accessPolicyName, Set.empty, Set.empty).some
      }
    } yield results.flatten

  def listUserManagedGroups(userId: WorkbenchUserId): IO[Set[ResourceIdAndPolicyName]] =
    for {
      ripns <- accessPolicyDAO.listAccessPolicies(ManagedGroupService.managedGroupTypeName, userId)
    } yield ripns.filter(ripn => ManagedGroupService.userMembershipPolicyNames.contains(ripn.accessPolicyName))
}

object PolicyEvaluatorService {
  def apply(resourceTypes: Map[ResourceTypeName, ResourceType], accessPolicyDAO: AccessPolicyDAO)(
      implicit executionContext: ExecutionContext): PolicyEvaluatorService =
    new PolicyEvaluatorService(resourceTypes, accessPolicyDAO)
}
