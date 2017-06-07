package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.sam.WorkbenchExceptionWithErrorReport
import org.broadinstitute.dsde.workbench.sam.model.{ErrorReport, ResourceType}

import org.broadinstitute.dsde.workbench.sam.service.ResourceService

import scala.concurrent.ExecutionContext

/**
  * Created by mbemis on 5/22/17.
  */
trait ResourceRoutes extends UserInfoDirectives {
  implicit val executionContext: ExecutionContext
  val resourceService: ResourceService
  val resourceTypes: Map[String, ResourceType]

  def resourceRoutes: server.Route =
    pathPrefix("resource") {
      requireUserInfo { userInfo =>
        pathPrefix(Segment / Segment) { (resourceTypeName, resourceId) =>
          val resourceType = resourceTypes.getOrElse(resourceTypeName, throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"resource type $resourceTypeName not found")))

          pathEndOrSingleSlash {
            post {
              complete(resourceService.createResource(resourceType, resourceId, userInfo).map(_ => StatusCodes.NoContent))
            }
          } ~
            pathPrefix("action") {
              pathPrefix(Segment) { action =>
                pathEndOrSingleSlash {
                  get {
                    complete(resourceService.hasPermission(resourceType, resourceId, action, userInfo))
                  }
                }
              }
            }
        }
      }
    }
}
