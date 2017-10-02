package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
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
trait ResourceRoutes extends UserInfoDirectives {
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

          pathEndOrSingleSlash {
            post {
              complete(resourceService.createResource(resourceType, ResourceName(resourceId), userInfo).map(_ => StatusCodes.NoContent))
            }
          } ~
          pathPrefix("action") {
            pathPrefix(Segment) { action =>
              pathEndOrSingleSlash {
                get {
                  complete(resourceService.hasPermission(resourceType.name, ResourceName(resourceId), ResourceAction(action), userInfo).map { hasPermission =>
                    StatusCodes.OK -> JsBoolean(hasPermission)
                  })
                }
              }
            }
          } ~
          pathPrefix("policies") {
            pathPrefix(Segment) { policyId =>
              pathEndOrSingleSlash {
                put {
                  entity(as[AccessPolicyMembershipExternal]) { membershipUpdate =>
                    println(membershipUpdate)
                    complete(resourceService.overwritePolicyMembership(policyId, Resource(resourceType.name, ResourceName(resourceId)), membershipUpdate).map(_ => StatusCodes.Created))
                  }
                }
              }
            }
          } ~
          pathPrefix("roles") {
            pathEndOrSingleSlash {
              get {
                complete(resourceService.listUserResourceRoles(resourceType.name, ResourceName(resourceId), userInfo).map { roles =>
                  StatusCodes.OK -> roles
                })
              }
            }
          }
        }
      }
    }
}
