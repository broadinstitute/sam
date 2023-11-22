package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes.{Created, NoContent, NotFound, OK}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.sam.config.LiquibaseConfig
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamResourceActions.{adminAddMember, adminReadPolicies, adminRemoveMember}
import org.broadinstitute.dsde.workbench.sam.model.SamResourceTypes.resourceTypeAdminName
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api.{AccessPolicyMembershipRequest, AdminUpdateUserRequest, SamUser}
import org.broadinstitute.dsde.workbench.sam.service.ResourceService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import spray.json.DefaultJsonProtocol._

import scala.concurrent.ExecutionContext

trait AdminRoutes extends SecurityDirectives with SamRequestContextDirectives with SamUserDirectives with SamModelDirectives {

  implicit val executionContext: ExecutionContext
  val resourceService: ResourceService
  val liquibaseConfig: LiquibaseConfig

  def adminRoutes(user: SamUser, requestContext: SamRequestContext): server.Route =
    pathPrefix("admin") {
      adminUserRoutes(user, requestContext) ~ pathPrefix("v1") {
        adminUserRoutes(user, requestContext) ~
        adminResourcesRoutes(user, requestContext) ~
        adminResourceTypesRoutes(user, requestContext)
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
          complete {
            userService
              .getUserStatusFromEmail(workbenchEmail, samRequestContext)
              .map(status => (if (status.isDefined) OK else NotFound) -> status)
          }
        } ~
        pathPrefix(Segment) { userId =>
          val workbenchUserId = WorkbenchUserId(userId)
          val userIdParam = "userId" -> workbenchUserId
          pathEnd {
            deleteWithTelemetry(samRequestContext, userIdParam) {
              complete {
                userService.deleteUser(workbenchUserId, samRequestContext).map(_ => OK)
              }
            } ~
            getWithTelemetry(samRequestContext, userIdParam) {
              complete {
                userService
                  .getUserStatus(workbenchUserId, samRequestContext = samRequestContext)
                  .map(status => (if (status.isDefined) OK else NotFound) -> status)
              }
            } ~
            patchWithTelemetry(samRequestContext, userIdParam) {
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
              putWithTelemetry(samRequestContext, userIdParam) {
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
              putWithTelemetry(samRequestContext, userIdParam) {
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
              putWithTelemetry(samRequestContext, userIdParam) {
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
              deleteWithTelemetry(samRequestContext, userIdParam, "googleProject" -> googleProject) {
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
          getWithTelemetry(samRequestContext, "userId" -> workbenchUserId) {
            complete {
              userService
                .getUser(workbenchUserId, samRequestContext = samRequestContext)
                .map(user => (if (user.isDefined) OK else NotFound) -> user)
            }
          }
        }
      }
    }

  def adminResourcesRoutes(user: SamUser, samRequestContext: SamRequestContext): server.Route =
    pathPrefix("resources" / Segment / Segment / "policies") { case (resourceTypeName, resourceId) =>
      withNonAdminResourceType(ResourceTypeName(resourceTypeName)) { resourceType =>
        val resource = FullyQualifiedResourceId(resourceType.name, ResourceId(resourceId))
        val resourceParams = Seq("resourceTypeName" -> resourceType.name, "resourceId" -> resource.resourceId)
        pathEndOrSingleSlash {
          getWithTelemetry(samRequestContext, resourceParams: _*) {
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
          val policyMemberParams = Seq("policyName" -> policyId.accessPolicyName, "userEmail" -> workbenchEmail)
          pathEndOrSingleSlash {
            withSubject(workbenchEmail, samRequestContext) { subject =>
              putWithTelemetry(samRequestContext, resourceParams ++ policyMemberParams: _*) {
                requireAdminResourceAction(adminAddMember, resourceType, user, samRequestContext) {
                  complete {
                    resourceService
                      .addSubjectToPolicy(policyId, subject, samRequestContext)
                      .as(NoContent)
                  }
                }
              } ~
              deleteWithTelemetry(samRequestContext, resourceParams ++ policyMemberParams: _*) {
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

  def adminResourceTypesRoutes(user: SamUser, samRequestContext: SamRequestContext): server.Route =
    pathPrefix("resourceTypes" / Segment / "policies") { resourceTypeNameToAdminister =>
      asSamSuperAdmin(user) {
        withNonAdminResourceType(ResourceTypeName(resourceTypeNameToAdminister)) { resourceTypeToAdminister =>
          val resource = FullyQualifiedResourceId(resourceTypeAdminName, ResourceId(resourceTypeToAdminister.name.value))
          pathEndOrSingleSlash {
            getWithTelemetry(samRequestContext, "resourceTypeName" -> resourceTypeToAdminister.name) {
              complete {
                resourceService
                  .listResourcePolicies(resource, samRequestContext)
                  .map(response => OK -> response.toSet)
              }
            }
          } ~
          pathPrefix(Segment) { policyName =>
            val policyId = FullyQualifiedPolicyId(resource, AccessPolicyName(policyName))
            val policyParams = Seq("resourceTypeName" -> resourceTypeToAdminister.name, "policyName" -> policyId.accessPolicyName)
            pathEndOrSingleSlash {
              putWithTelemetry(samRequestContext, policyParams: _*) {
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
              deleteWithTelemetry(samRequestContext, policyParams: _*) {
                complete(resourceService.deletePolicy(policyId, samRequestContext).as(NoContent))
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
