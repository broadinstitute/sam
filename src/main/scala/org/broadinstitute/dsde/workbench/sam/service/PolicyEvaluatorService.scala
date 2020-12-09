package org.broadinstitute.dsde.workbench.sam.service

import akka.http.scaladsl.model.StatusCodes
import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.{AccessPolicyDAO, LoadResourceAuthDomainResult}
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService.MangedGroupRoleName
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
          Set.empty,
          true
        ), SamRequestContext(None)) // `SamRequestContext(None)` is used so that we don't trace 1-off boot/init methods
      .void
      .recoverWith {
        case duplicateException: WorkbenchExceptionWithErrorReport if duplicateException.errorReport.statusCode.contains(StatusCodes.Conflict) =>
          IO(logger.debug(s"$policyName has already been created"))
      }
  }

  def hasPermissionOneOf(resource: FullyQualifiedResourceId, actions: Iterable[ResourceAction], userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Boolean] = traceIOWithContext("hasPermissionOneOf", samRequestContext)(samRequestContext => {
    listUserResourceActions(resource, userId, samRequestContext).map { userActions =>
      actions.toSet.intersect(userActions).nonEmpty
    }
  })

  def hasPermission(resource: FullyQualifiedResourceId, action: ResourceAction, userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Boolean] = traceIOWithContext("hasPermission", samRequestContext)(samRequestContext => {
    hasPermissionOneOf(resource, Set(action), userId, samRequestContext)
  })

  /** Checks if user have permission by providing user email address. */
  def hasPermissionByUserEmail(resource: FullyQualifiedResourceId, action: ResourceAction, userEmail: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Boolean] = traceIOWithContext("hasPermissionByUserEmail", samRequestContext)(samRequestContext => {
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
    * @return
    */
  def listUserResourceActions(resource: FullyQualifiedResourceId, userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[ResourceAction]] = {
    for {
      rt <- IO.fromEither[ResourceType](
        resourceTypes.get(resource.resourceTypeName).toRight(new WorkbenchException(s"missing configuration for resourceType ${resource.resourceTypeName}")))
      isConstrainable = rt.isAuthDomainConstrainable

      allPolicyActions <- accessPolicyDAO.listUserResourceActions(resource, userId, samRequestContext)
      res <- if (isConstrainable) {
        for {
          authDomainsResult <- accessPolicyDAO.loadResourceAuthDomain(resource, samRequestContext)
          policyActions <- authDomainsResult match {
            case LoadResourceAuthDomainResult.Constrained(authDomains) =>
              isMemberOfAllAuthDomainGroups(authDomains, userId, samRequestContext).map {
                case true => allPolicyActions
                case false =>
                  val constrainableActions = rt.actionPatterns.filter(_.authDomainConstrainable)
                  allPolicyActions.filterNot(x => constrainableActions.exists(_.matches(x)))
              }
            case LoadResourceAuthDomainResult.NotConstrained | LoadResourceAuthDomainResult.ResourceNotFound =>
              allPolicyActions.pure[IO]
          }
        } yield policyActions
      } else allPolicyActions.pure[IO]
    } yield res
  }

  private def isMemberOfAllAuthDomainGroups(authDomains: NonEmptyList[WorkbenchGroupName], userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Boolean] = {
    // This checks each auth domain group sequentially. At this time in production there are 9334 resources
    // with 1 AD group, 302 with 2, 57 with more than 2. Obviously this gets slower with more groups but that should
    // only be a problem under high load. To fix try switching the traverse below to parTraverse.
    authDomains.toList.traverse { adGroup =>
      val groupId = FullyQualifiedResourceId(ManagedGroupService.managedGroupTypeName, ResourceId(adGroup.value))
      accessPolicyDAO.listUserResourceRoles(groupId, userId, samRequestContext).map { userRoles =>
        userRoles.intersect(ManagedGroupService.userMembershipRoleNames).nonEmpty
      }
    }.map(_.reduce(_ && _)) // reduce makes sure all individual checks are true
  }

  @deprecated("listing policies for resource type removed, use listUserResources instead", since = "ResourceRoutes v2")
  def listUserAccessPolicies(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[UserPolicyResponse]] =
    for {
      rt <- getResourceType(resourceTypeName)
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

  def listUserResources(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Iterable[UserResourcesResponse]] =
    for {
      rt <- getResourceType(resourceTypeName)
      resourcesWithAccess <- accessPolicyDAO.listUserResourcesWithRolesAndActions(resourceTypeName, userId, samRequestContext)
      userResourcesResponses = resourcesWithAccess.map { resourceWithAccess =>
        UserResourcesResponse(resourceWithAccess.resourceId, resourceWithAccess.direct, resourceWithAccess.inherited, resourceWithAccess.public, Set.empty, Set.empty)
      }

      results <- if (rt.isAuthDomainConstrainable) {
        populateAuthDomains(resourceTypeName, userId, userResourcesResponses, samRequestContext)
      } else {
        IO.pure(userResourcesResponses.map(_.some))
      }
    } yield results.flatten

  private def populateAuthDomains(resourceTypeName: ResourceTypeName, userId: WorkbenchUserId, userResourcesResponses: Iterable[UserResourcesResponse], samRequestContext: SamRequestContext) = {
    for {
      resources <- accessPolicyDAO.listResourcesWithAuthdomains(resourceTypeName, userResourcesResponses.map(_.resourceId).toSet, samRequestContext)
      userManagedGroups <- listUserManagedGroups(userId, samRequestContext)
    } yield {
      val authDomainMap = resources.map(resource => resource.resourceId -> resource.authDomain).toMap
      userResourcesResponses.map { response =>
        authDomainMap.get(response.resourceId) match {
          case Some(resourceAuthDomains) =>
            val userNotMemberOf = resourceAuthDomains -- userManagedGroups
            response.copy(authDomainGroups = resourceAuthDomains, missingAuthDomainGroups = userNotMemberOf).some
          case None =>
            logger.error(s"Corrupted data. ${response.resourceId} should have auth domains defined")
            none[UserResourcesResponse]
        }
      }
    }
  }

  private def getResourceType(resourceTypeName: ResourceTypeName): IO[ResourceType] = {
    IO.fromEither(
      resourceTypes
        .get(resourceTypeName)
        .toRight(new WorkbenchException(s"missing configration for resourceType ${resourceTypeName}")))
  }

  def listUserManagedGroupsWithRole(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Iterable[ManagedGroupAndRole]] =
    accessPolicyDAO.listUserResourcesWithRolesAndActions(ManagedGroupService.managedGroupTypeName, userId, samRequestContext) map { accessibleGroupResources =>
      for {
        groupResource <- accessibleGroupResources
        roleName <- groupResource.allRolesAndActions.roles.collect {
          case MangedGroupRoleName(roleName) if ManagedGroupService.userMembershipRoleNames.contains(roleName) => roleName
        }
      } yield {
        ManagedGroupAndRole(WorkbenchGroupName(groupResource.resourceId.value), roleName)
      }
    }

  def listUserManagedGroups(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupName]] =
    for {
      policies <- listUserManagedGroupsWithRole(userId, samRequestContext)
    } yield {
      policies.map(_.groupName)
    }.toSet
}

object PolicyEvaluatorService {
  def apply(emailDomain: String, resourceTypes: Map[ResourceTypeName, ResourceType], accessPolicyDAO: AccessPolicyDAO, directoryDAO: DirectoryDAO)(
      implicit executionContext: ExecutionContext): PolicyEvaluatorService =
    new PolicyEvaluatorService(emailDomain, resourceTypes, accessPolicyDAO, directoryDAO)
}
