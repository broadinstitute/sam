package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.{Created, NoContent, NotFound, OK}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server.Directives._
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.sam.config.LiquibaseConfig
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamResourceActions.{adminAddMember, adminReadPolicies, adminRemoveMember}
import org.broadinstitute.dsde.workbench.sam.model.SamResourceTypes.resourceTypeAdminName
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api.{AccessPolicyMembershipRequest, AdminUpdateUserRequest, SamUser}
import org.broadinstitute.dsde.workbench.sam.service.{ManagedGroupService, ResourceService}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import spray.json.DefaultJsonProtocol._
import spray.json.JsBoolean
import org.broadinstitute.dsde.workbench.sam.model.api.ManagedGroupModelJsonSupport._

import scala.concurrent.ExecutionContext

trait AdminRoutes extends SecurityDirectives with SamRequestContextDirectives with SamUserDirectives with SamModelDirectives {

  implicit val executionContext: ExecutionContext
  val resourceService: ResourceService
  val managedGroupService: ManagedGroupService
  val liquibaseConfig: LiquibaseConfig

  def adminRoutes(user: SamUser, requestContext: SamRequestContext): server.Route =
    pathPrefix("admin") {
      adminUserRoutes(user, requestContext) ~ pathPrefix("v1") {
        adminUserRoutes(user, requestContext) ~
        adminResourcesRoutes(user, requestContext) ~
        adminResourceTypesRoutes(user, requestContext) ~
        adminGroupsRoutes(user, requestContext)
      } ~ pathPrefix("v2") {
        asWorkbenchAdmin(user) {
          adminUserRoutesV2(user, requestContext)
        }
      }
    }

  def adminUserRoutes(samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    pathPrefix("user") {
      asWorkbenchAdmin(samUser) {
        path("email" / Segment) { email =>
          val workbenchEmail = WorkbenchEmail(email)
          getWithTelemetry(samRequestContext, emailParam(workbenchEmail)) {
            complete {
              userService
                .getUserStatusFromEmail(workbenchEmail, samRequestContext)
                .map(status => (if (status.isDefined) OK else NotFound) -> status)
            }
          }
        } ~
        pathPrefix(Segment) { userId =>
          val workbenchUserId = WorkbenchUserId(userId)
          pathEnd {
            deleteWithTelemetry(samRequestContext, userIdParam(workbenchUserId)) {
              complete {
                userService.deleteUser(workbenchUserId, samRequestContext).map(_ => OK)
              }
            } ~
            getWithTelemetry(samRequestContext, userIdParam(workbenchUserId)) {
              complete {
                userService
                  .getUserStatus(workbenchUserId, samRequestContext = samRequestContext)
                  .map(status => (if (status.isDefined) OK else NotFound) -> status)
              }
            } ~
            patchWithTelemetry(samRequestContext, userIdParam(workbenchUserId)) {
              entity(as[AdminUpdateUserRequest]) { request =>
                complete {
                  userService
                    .updateUserCrud(workbenchUserId, request, samRequestContext)
                    .map(user => (if (user.isDefined) OK else NotFound) -> user)
                }
              }
            }
          } ~
          pathPrefix("enable") {
            pathEndOrSingleSlash {
              putWithTelemetry(samRequestContext, userIdParam(workbenchUserId)) {
                complete {
                  userService
                    .enableUser(workbenchUserId, samRequestContext)
                    .map(status => (if (status.isDefined) OK else NotFound) -> status)
                }
              }
            }
          } ~
          pathPrefix("disable") {
            pathEndOrSingleSlash {
              putWithTelemetry(samRequestContext, userIdParam(workbenchUserId)) {
                complete {
                  userService
                    .disableUser(workbenchUserId, samRequestContext)
                    .map(status => (if (status.isDefined) OK else NotFound) -> status)
                }
              }
            }
          } ~
          // This will get removed once ID-87 is resolved
          pathPrefix("repairAllUsersGroup") {
            pathEndOrSingleSlash {
              putWithTelemetry(samRequestContext, userIdParam(workbenchUserId)) {
                complete {
                  userService
                    .addToAllUsersGroup(workbenchUserId, samRequestContext)
                    .map(_ => OK)
                }
              }
            }
          } ~
          pathPrefix("petServiceAccount") {
            path(Segment) { project =>
              val googleProject = GoogleProject(project)
              deleteWithTelemetry(samRequestContext, userIdParam(workbenchUserId), googleProjectParam(googleProject)) {
                complete {
                  cloudExtensions
                    .deleteUserPetServiceAccount(workbenchUserId, googleProject, samRequestContext)
                    .map(_ => NoContent)
                }
              }
            }
          }
        }
      }
    }

  private def adminUserRoutesV2(user: SamUser, samRequestContext: SamRequestContext): server.Route =
    pathPrefix("user") {
      pathPrefix(Segment) { userId =>
        val workbenchUserId = WorkbenchUserId(userId)
        pathEnd {
          getWithTelemetry(samRequestContext, userIdParam(workbenchUserId)) {
            complete {
              userService
                .getUser(workbenchUserId, samRequestContext = samRequestContext)
                .map(user => (if (user.isDefined) OK else NotFound) -> user)
            }
          }
        } ~
        pathPrefix("repairCloudAccess") {
          pathEndOrSingleSlash {
            putWithTelemetry(samRequestContext, userIdParam(workbenchUserId)) {
              complete {
                userService
                  .repairCloudAccess(workbenchUserId, samRequestContext)
                  .map(_ => NoContent)
              }
            }
          }
        }
      }
    }

  def adminResourcesRoutes(user: SamUser, samRequestContext: SamRequestContext): server.Route =
    pathPrefix("resources" / Segment / Segment / "policies") { case (resourceTypeName, resourceId) =>
      withNonAdminResourceType(ResourceTypeName(resourceTypeName)) { resourceType =>
        val resource = FullyQualifiedResourceId(resourceType.name, ResourceId(resourceId))
        pathEndOrSingleSlash {
          getWithTelemetry(samRequestContext, resourceParams(resource): _*) {
            requireAdminResourceAction(adminReadPolicies, resourceType, user, samRequestContext) {
              complete {
                resourceService
                  .listResourcePolicies(resource, samRequestContext)
                  .map(response => OK -> response.toSet)
              }
            }
          }
        } ~
        pathPrefix(Segment / "memberEmails" / Segment) { case (policyName, userEmail) =>
          val policyId = FullyQualifiedPolicyId(resource, AccessPolicyName(policyName))
          val workbenchEmail = WorkbenchEmail(userEmail)
          pathEndOrSingleSlash {
            withSubject(workbenchEmail, samRequestContext) { subject =>
              putWithTelemetry(samRequestContext, policyParams(policyId).appended(emailParam(workbenchEmail)): _*) {
                requireAdminResourceAction(adminAddMember, resourceType, user, samRequestContext) {
                  complete {
                    resourceService
                      .addSubjectToPolicy(policyId, subject, samRequestContext)
                      .as(NoContent)
                  }
                }
              } ~
              deleteWithTelemetry(samRequestContext, policyParams(policyId).appended(emailParam(workbenchEmail)): _*) {
                requireAdminResourceAction(adminRemoveMember, resourceType, user, samRequestContext) {
                  complete {
                    resourceService
                      .removeSubjectFromPolicy(policyId, subject, samRequestContext)
                      .as(NoContent)
                  }
                }
              }
            }
          }
        }
      }
    }

  def adminGroupsRoutes(user: SamUser, samRequestContext: SamRequestContext): server.Route =
    path("groups" / Segment / "supportSummary") { groupName =>
      pathEndOrSingleSlash {
        withResourceType(ManagedGroupService.managedGroupTypeName) { managedGroupType =>
          val groupId = FullyQualifiedResourceId(ManagedGroupService.managedGroupTypeName, ResourceId(groupName))
          getWithTelemetry(samRequestContext, groupIdParam(groupId)) {
            requireAdminResourceAction(SamResourceActions.adminReadSummaryInformation, managedGroupType, user, samRequestContext) {
              complete {
                managedGroupService.loadManagedGroupSupportSummary(groupId.resourceId, samRequestContext).flatMap {
                  case Some(response) => IO.pure(StatusCodes.OK -> response)
                  case None => IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "group not found")))
                }
              }
            }
          }
        }
      }
    }

  def adminResourceTypesRoutes(user: SamUser, samRequestContext: SamRequestContext): server.Route =
    pathPrefix("resourceTypes" / Segment) { resourceTypeNameToAdminister =>
      pathPrefix("action" / Segment) { actionString =>
        // a similar api exists in ResourceRoutes, but that one does not allow access to resourceTypeAdmin
        // this one is also under /admin so it is only accessible on the Broad network
        val action = ResourceAction(actionString)
        val resource = FullyQualifiedResourceId(resourceTypeAdminName, ResourceId(resourceTypeNameToAdminister))
        pathEndOrSingleSlash {
          getWithTelemetry(samRequestContext, resourceParams(resource).appended(actionParam(action)): _*) {
            complete {
              policyEvaluatorService.hasPermission(resource, action, user.id, samRequestContext).map { hasPermission =>
                StatusCodes.OK -> JsBoolean(hasPermission)
              }
            }
          }
        }
      } ~
      pathPrefix("policies") {
        asSamSuperAdmin(user) {
          withNonAdminResourceType(ResourceTypeName(resourceTypeNameToAdminister)) { resourceTypeToAdminister =>
            val resource = FullyQualifiedResourceId(resourceTypeAdminName, ResourceId(resourceTypeToAdminister.name.value))
            pathEndOrSingleSlash {
              getWithTelemetry(samRequestContext, resourceTypeParam(resourceTypeToAdminister)) {
                complete {
                  resourceService
                    .listResourcePolicies(resource, samRequestContext)
                    .map(response => OK -> response.toSet)
                }
              }
            } ~
            pathPrefix(Segment) { policyName =>
              val policyId = FullyQualifiedPolicyId(resource, AccessPolicyName(policyName))
              pathEndOrSingleSlash {
                putWithTelemetry(samRequestContext, policyParams(policyId): _*) {
                  entity(as[AccessPolicyMembershipRequest]) { membershipUpdate =>
                    withResourceType(resourceTypeAdminName) { resourceTypeAdmin =>
                      complete {
                        resourceService
                          .overwriteAdminPolicy(resourceTypeAdmin, policyId.accessPolicyName, policyId.resource, membershipUpdate, samRequestContext)
                          .as(Created)
                      }
                    }
                  }
                } ~
                deleteWithTelemetry(samRequestContext, policyParams(policyId): _*) {
                  complete(resourceService.deletePolicy(policyId, samRequestContext).as(NoContent))
                }
              }
            }
          }
        }
      }
    }

  def requireAdminResourceAction(action: ResourceAction, resourceType: ResourceType, user: SamUser, samRequestContext: SamRequestContext): Directive0 =
    requireAction(
      FullyQualifiedResourceId(resourceTypeAdminName, ResourceId(resourceType.name.value)),
      action,
      user.id,
      samRequestContext
    )
}
