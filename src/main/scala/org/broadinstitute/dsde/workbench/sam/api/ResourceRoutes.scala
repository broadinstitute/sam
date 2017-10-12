package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.{Directive0, Directives}
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.workbench.sam.service.ResourceService
import spray.json.JsBoolean

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 5/22/17.
  */
trait ResourceRoutes extends UserInfoDirectives with SecurityDirectives {
  implicit val executionContext: ExecutionContext
  val resourceService: ResourceService
  val resourceTypes: Map[ResourceTypeName, ResourceType]

  def resourceRoutes: server.Route =
    pathPrefix("resourceTypes") {
      requireUserInfo { userInfo =>
        pathEndOrSingleSlash {
          get {
            complete {
              StatusCodes.OK -> resourceTypes.values.toSet
            }
          }
        }
      }
    } ~
    pathPrefix("resource") {
      requireUserInfo { userInfo =>
        pathPrefix(Segment / Segment) { (resourceTypeName, resourceId) =>
          val resourceType = resourceTypes.getOrElse(ResourceTypeName(resourceTypeName), throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"resource type $resourceTypeName not found")))

          val resource = Resource(resourceType.name, ResourceId(resourceId))
          pathEndOrSingleSlash {
            delete {
              requireAction(resource, SamResourceActions.delete, userInfo) {
                complete(resourceService.deleteResource(resource, userInfo).map(_ => StatusCodes.NoContent))
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
                  complete(resourceService.hasPermission(resource, ResourceAction(action), userInfo).map { hasPermission =>
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
                  complete(resourceService.listResourcePolicies(resource, userInfo).map { response =>
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
                      complete(resourceService.overwritePolicy(resourceType, policyName, resource, membershipUpdate, userInfo).map(_ => StatusCodes.Created))
                    }
                  }
                }
              }
            }
          } ~
          pathPrefix("roles") {
            pathEndOrSingleSlash {
              get {
                complete(resourceService.listUserResourceRoles(resource, userInfo).map { roles =>
                  StatusCodes.OK -> roles
                })
              }
            }
          }
        }
      }
    }
}
