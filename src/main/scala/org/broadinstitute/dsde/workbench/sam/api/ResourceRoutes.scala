package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.{Directive0, Directive1, Directives}
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.workbench.sam.service.ResourceService
import spray.json.JsBoolean

import scala.concurrent.ExecutionContext

/**
  * Created by mbemis on 5/22/17.
  */
trait ResourceRoutes extends UserInfoDirectives with SecurityDirectives {
  implicit val executionContext: ExecutionContext
  val resourceService: ResourceService

  def withResourceType(name: ResourceTypeName): Directive1[ResourceType] = {
    onSuccess(resourceService.getResourceType(name)).map {
      case Some(resourceType) => resourceType
      case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"resource type ${name.value} not found"))
    }
  }

  def resourceRoutes: server.Route =
    pathPrefix("resourceTypes") {
      requireUserInfo { userInfo =>
        pathEndOrSingleSlash {
          get {
            complete {
              resourceService.getResourceTypes().map(typeMap => StatusCodes.OK -> typeMap.values.toSet)
            }
          }
        }
      }
    } ~
    pathPrefix("resource") {
      requireUserInfo { userInfo =>
        pathPrefix(Segment) { resourceTypeName =>
          withResourceType(ResourceTypeName(resourceTypeName)) { resourceType =>
            pathEndOrSingleSlash {
              complete(resourceService.listUserAccessPolicies(resourceType, userInfo))
            } ~
            pathPrefix(Segment) { resourceId =>

              val resource = Resource(resourceType.name, ResourceId(resourceId))

              pathEndOrSingleSlash {
                delete {
                  requireAction(resource, SamResourceActions.delete, userInfo) {
                    complete(resourceService.deleteResource(Resource(resourceType.name, ResourceId(resourceId)), userInfo).map(_ => StatusCodes.NoContent))
                  }
                } ~
                  post {
                    complete(resourceService.createResource(resourceType, ResourceId(resourceId), userInfo).map(_ => StatusCodes.NoContent))
                  }
              } ~
              pathPrefix("action") {
                pathPrefix(Segment) { action =>
                  pathEndOrSingleSlash {
                    get {
                      complete(resourceService.hasPermission(Resource(resourceType.name, ResourceId(resourceId)), ResourceAction(action), userInfo).map { hasPermission =>
                        StatusCodes.OK -> JsBoolean(hasPermission)
                      })
                    }
                  }
                }
              } ~
              pathPrefix("policies") {
                pathEndOrSingleSlash {
                  get {
                    requireAction(resource, SamResourceActions.readPolicies, userInfo) {
                      complete(resourceService.listResourcePolicies(Resource(resourceType.name, ResourceId(resourceId)), userInfo).map { response =>
                        StatusCodes.OK -> response
                      })
                    }
                  }
                } ~
                  pathPrefix(Segment) { policyName =>
                    pathEndOrSingleSlash {
                      put {
                        requireAction(resource, SamResourceActions.alterPolicies, userInfo) {
                          entity(as[AccessPolicyMembership]) { membershipUpdate =>
                            complete(resourceService.overwritePolicy(resourceType, AccessPolicyName(policyName), Resource(resourceType.name, ResourceId(resourceId)), membershipUpdate, userInfo).map(_ => StatusCodes.Created))
                          }
                        }
                      }
                    }
                  }
              } ~
              pathPrefix("roles") {
                pathEndOrSingleSlash {
                  get {
                    complete(resourceService.listUserResourceRoles(Resource(resourceType.name, ResourceId(resourceId)), userInfo).map { roles =>
                      StatusCodes.OK -> roles
                    })
                  }
                }
              }
            }
          }
        }
      }
    }
}
