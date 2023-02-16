package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.sam.service.StatusService
import org.broadinstitute.dsde.workbench.util.health.StatusJsonSupport._

import scala.concurrent.ExecutionContext

trait MockStatusRoutes {
  val statusService: StatusService
  implicit val executionContext: ExecutionContext

  def statusRoutes: server.Route =
    pathPrefix("status") {
      pathEndOrSingleSlash {
        get {
          complete {
            statusService.getStatus().map { statusResponse =>
              val httpStatus = if (statusResponse.ok) {
                StatusCodes.OK
              } else {
                StatusCodes.InternalServerError
              }
              (httpStatus, statusResponse)
            }
          }
        }
      }
    } ~
      pathPrefix("version") {
        pathEndOrSingleSlash {
          get {
            complete {
              (StatusCodes.OK, BuildTimeVersion.versionJson)
            }
          }
        }
      }
}
