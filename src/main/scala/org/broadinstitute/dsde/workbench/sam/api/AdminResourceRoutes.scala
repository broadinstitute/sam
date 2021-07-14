package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model.UserInfo
import org.broadinstitute.dsde.workbench.sam.config.LiquibaseConfig
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.ResourceService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.ExecutionContext

trait AdminResourceRoutes extends UserInfoDirectives with SecurityDirectives with SamRequestContextDirectives with ResourceRoutes {
  implicit val executionContext: ExecutionContext
  val resourceService: ResourceService
  val liquibaseConfig: LiquibaseConfig

  def adminResourceRoutes: server.Route =
    pathPrefix("resourceTypeAdmin" / "v1") {
      withSamRequestContext { samRequestContext =>
        requireUserInfo(samRequestContext) { userInfo =>
          withResourceType(SamResourceTypes.resourceTypeAdminName) { resourceTypeAdmin =>
            pathPrefix("resourceTypes") {
              asSamSuperAdmin(userInfo) {
                pathPrefix(Segment) { resourceTypeNameToAdminister =>
                  pathPrefix("policies") {
                    pathEndOrSingleSlash {
                      get {
                        complete(StatusCodes.OK) // TODO: CA-1248
                      }
                    } ~
                      pathPrefix(Segment) { policyName =>
                        val policyId = FullyQualifiedPolicyId(FullyQualifiedResourceId(SamResourceTypes.resourceTypeAdminName, ResourceId(resourceTypeNameToAdminister)), AccessPolicyName(policyName))
                        pathEndOrSingleSlash {
                          put {
                            entity(as[AccessPolicyMembership]) { membershipUpdate =>
                              complete(resourceService.overwritePolicy(resourceTypeAdmin, policyId.accessPolicyName, policyId.resource, membershipUpdate, samRequestContext).map(_ => StatusCodes.Created))
                            }
                          } ~
                            delete {
                              complete(resourceService.deletePolicy(policyId, samRequestContext).map(_ => StatusCodes.NoContent))
                            }
                        }
                      }
                  }
                }
              }
            }
            pathPrefix("resources") {
              pathPrefix(Segment) { resourceTypeNameToAdminister =>
                withResourceType(ResourceTypeName(resourceTypeNameToAdminister)) { resourceTypeToAdminister =>
                  pathPrefix(Segment) { resourceId =>
                    pathPrefix("policies") {
                      pathEndOrSingleSlash {
                        val resource = FullyQualifiedResourceId(ResourceTypeName(resourceTypeNameToAdminister), ResourceId(resourceId))
                        getAdminResourcePolicies(resource, userInfo, samRequestContext)
                      } ~
                        pathPrefix(Segment) { policyName =>
                          pathPrefix("memberEmails" / Segment) { userEmail =>
                            put {
                              complete(StatusCodes.OK) // TODO: CA-1245
                            } ~
                              delete {
                                complete(StatusCodes.OK) // TODO: CA-1246
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
    }

  def getAdminResourcePolicies(resource: FullyQualifiedResourceId, userInfo: UserInfo, samRequestContext: SamRequestContext): server.Route =
    get {
      requireAction(resource, SamResourceActions.adminReadPolicies, userInfo.userId, samRequestContext) {
        complete(resourceService.listResourcePolicies(resource, samRequestContext).map { response =>
          StatusCodes.OK -> response.toSet
        })
      }
    }

}
