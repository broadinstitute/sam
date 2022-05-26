package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.{Created, NoContent, OK}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.sam.config.LiquibaseConfig
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamResourceActions.{adminAddMember, adminReadPolicies, adminRemoveMember}
import org.broadinstitute.dsde.workbench.sam.model.SamResourceTypes.resourceTypeAdminName
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.ResourceService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import spray.json.DefaultJsonProtocol._

import scala.concurrent.ExecutionContext

trait AdminRoutes
  extends SecurityDirectives
    with SamRequestContextDirectives
    with SamUserDirectives
    with SamModelDirectives {

  implicit val executionContext: ExecutionContext
  val resourceService: ResourceService
  val liquibaseConfig: LiquibaseConfig

  def adminRoutes(user: SamUser, requestContext: SamRequestContext): server.Route =
    pathPrefix("admin") {
      adminUserRoutes(user, requestContext) ~ pathPrefix("v1") {
        adminUserRoutes(user, requestContext) ~
          adminResourceTypesRoutes(user, requestContext) ~
          adminResourcesRoutes(user, requestContext)
      }
    }

  def adminUserRoutes(samUser: SamUser, samRequestContext: SamRequestContext): server.Route =
    pathPrefix("user") {
      asWorkbenchAdmin(samUser) {
        path("email" / Segment) { email =>
          complete {
            userService.getUserStatusFromEmail(WorkbenchEmail(email), samRequestContext).map { statusOption =>
              statusOption
                .map { status =>
                  StatusCodes.OK -> Option(status)
                }
                .getOrElse(StatusCodes.NotFound -> None)
            }
          }
        } ~
          pathPrefix(Segment) { userId =>
            pathEnd {
              delete {
                complete {
                  userService.deleteUser(WorkbenchUserId(userId), samRequestContext).map(_ => StatusCodes.OK)
                }
              } ~
                get {
                  complete {
                    userService.getUserStatus(WorkbenchUserId(userId), samRequestContext = samRequestContext).map { statusOption =>
                      statusOption
                        .map { status =>
                          StatusCodes.OK -> Option(status)
                        }
                        .getOrElse(StatusCodes.NotFound -> None)
                    }
                  }
                }
            } ~
              pathPrefix("enable") {
                pathEndOrSingleSlash {
                  put {
                    complete {
                      userService.enableUser(WorkbenchUserId(userId), samRequestContext).map { statusOption =>
                        statusOption
                          .map { status =>
                            StatusCodes.OK -> Option(status)
                          }
                          .getOrElse(StatusCodes.NotFound -> None)
                      }
                    }
                  }
                }
              } ~
              pathPrefix("disable") {
                pathEndOrSingleSlash {
                  put {
                    complete {
                      userService.disableUser(WorkbenchUserId(userId), samRequestContext).map { statusOption =>
                        statusOption
                          .map { status =>
                            StatusCodes.OK -> Option(status)
                          }
                          .getOrElse(StatusCodes.NotFound -> None)
                      }
                    }
                  }
                }
              } ~
              pathPrefix("petServiceAccount") {
                path(Segment) { project =>
                  delete {
                    complete {
                      cloudExtensions
                        .deleteUserPetServiceAccount(WorkbenchUserId(userId), GoogleProject(project), samRequestContext)
                        .map(_ => StatusCodes.NoContent)
                    }
                  }
                }
              }
          }
      }
    }


  def adminResourceTypesRoutes(user: SamUser, samRequestContext: SamRequestContext): server.Route =
    pathPrefix("resourceTypes" / Segment / "policies") { resourceTypeNameToAdminister =>
      withNonAdminResourceType(ResourceTypeName(resourceTypeNameToAdminister)) { resourceTypeToAdminister =>
        asSamSuperAdmin(user) {
          val resource = FullyQualifiedResourceId(resourceTypeAdminName, ResourceId(resourceTypeToAdminister.name.value))
          pathEndOrSingleSlash {
            get {
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
                put {
                  entity(as[AccessPolicyMembership]) { membershipUpdate =>
                    withResourceType(resourceTypeAdminName) { resourceTypeAdmin =>
                      complete {
                        resourceService
                          .overwriteAdminPolicy(resourceTypeAdmin, policyId.accessPolicyName, policyId.resource, membershipUpdate, samRequestContext)
                          .as(Created)
                      }
                    }
                  }
                } ~
                  delete {
                    complete(resourceService.deletePolicy(policyId, samRequestContext).as(NoContent))
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
          get {
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
            pathEndOrSingleSlash {
              withSubject(WorkbenchEmail(userEmail), samRequestContext) { subject =>
                put {
                  requireAdminResourceAction(adminAddMember, resourceType, user, samRequestContext) {
                    complete {
                      resourceService
                        .addSubjectToPolicy(policyId, subject, samRequestContext)
                        .as(NoContent)
                    }
                  }
                } ~
                  delete {
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

  def requireAdminResourceAction(action: ResourceAction,
                                 resourceType: ResourceType,
                                 user: SamUser,
                                 samRequestContext: SamRequestContext): Directive0 =
    requireAction(
      FullyQualifiedResourceId(resourceTypeAdminName, ResourceId(resourceType.name.value)),
      action,
      user.id,
      samRequestContext
    )
}
