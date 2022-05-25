package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes.{Created, NoContent, OK}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.config.LiquibaseConfig
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamResourceActions.{adminAddMember, adminReadPolicies, adminRemoveMember}
import org.broadinstitute.dsde.workbench.sam.model.SamResourceTypes.resourceTypeAdminName
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.ResourceService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import spray.json.DefaultJsonProtocol._

import scala.concurrent.ExecutionContext

trait AdminResourceRoutes
  extends SecurityDirectives
    with SamRequestContextDirectives
    with SamUserDirectives
    with SamModelDirectives {

  implicit val executionContext: ExecutionContext
  val resourceService: ResourceService
  val liquibaseConfig: LiquibaseConfig

  def adminResourceRoutes(user: SamUser, samRequestContext: SamRequestContext): server.Route =
    pathPrefix("resourceTypeAdmin" / "v1") {
      pathPrefix("resourceTypes") {
        pathPrefix(Segment) { resourceTypeNameToAdminister =>
          withResourceType(ResourceTypeName(resourceTypeNameToAdminister)) { resourceTypeToAdminister =>
            asSamSuperAdmin(user) {
              val resource = FullyQualifiedResourceId(resourceTypeAdminName, ResourceId(resourceTypeToAdminister.name.value))
              pathPrefix("policies") {
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
        }
      } ~
        pathPrefix("resources") {
          pathPrefix(Segment) { resourceTypeNameToAdminister =>
            withResourceType(ResourceTypeName(resourceTypeNameToAdminister)) { resourceType =>
              pathPrefix(Segment) { resourceId =>
                val resource = FullyQualifiedResourceId(resourceType.name, ResourceId(resourceId))
                pathPrefix("policies") {
                  pathEndOrSingleSlash {
                    get {
                      requireAdminAction(adminReadPolicies, resourceType, user, samRequestContext) {
                        complete {
                          resourceService
                            .listResourcePolicies(resource, samRequestContext)
                            .map(response => OK -> response.toSet)
                        }
                      }
                    }
                  }
                } ~
                  pathPrefix(Segment) { policyName =>
                    val policyId = FullyQualifiedPolicyId(resource, AccessPolicyName(policyName))
                    pathPrefix("memberEmails" / Segment) { userEmail =>
                      withSubject(WorkbenchEmail(userEmail), samRequestContext) { subject =>
                        put {
                          requireAdminAction(adminAddMember, resourceType, user, samRequestContext) {
                            complete {
                              resourceService
                                .addSubjectToPolicy(policyId, subject, samRequestContext)
                                .as(NoContent)
                            }
                          }
                        } ~
                          delete {
                            requireAdminAction(adminRemoveMember, resourceType, user, samRequestContext) {
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
          }
        }
    }

  def requireAdminAction(action: ResourceAction, resourceType: ResourceType, user: SamUser, samRequestContext: SamRequestContext): Directive0 =
    requireAction(
      FullyQualifiedResourceId(resourceTypeAdminName, ResourceId(resourceType.name.value)),
      action,
      user.id,
      samRequestContext
    )
}
