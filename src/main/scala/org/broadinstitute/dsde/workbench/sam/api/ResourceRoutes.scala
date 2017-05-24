package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._

import scala.concurrent.ExecutionContext.Implicits.global
import org.broadinstitute.dsde.workbench.sam.service.ResourceService

import scala.concurrent.ExecutionContext

/**
  * Created by mbemis on 5/22/17.
  */
class ResourceRoutes(val resourceService: ResourceService)(implicit val executionContext: ExecutionContext) {

  def route: server.Route =
    pathPrefix("resource") {
      pathPrefix(Segment / Segment) { (resourceType, resourceId) =>
        pathEndOrSingleSlash {
          post {
            complete(resourceService.createResource(resourceType, resourceId))
          }
        } ~
        pathPrefix("action") {
          pathPrefix(Segment) { action =>
            pathEndOrSingleSlash {
              get {
                complete(resourceService.hasPermission(resourceType, resourceId, action))
              }
            }
          }
        }
      }
    }

}
