package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.sam.service.StatusService
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import spray.json.{JsObject, JsString}
import org.broadinstitute.dsde.workbench.util.health.StatusJsonSupport._
import org.broadinstitute.dsde.workbench.sam.util.OpenCensusIOUtils.completeWithTrace

import scala.concurrent.ExecutionContext

object BuildTimeVersion {
  val version = Option(getClass.getPackage.getImplementationVersion)
  val versionJson = JsObject(Map("version" -> JsString(version.getOrElse("n/a"))))
}

trait StatusRoutes {
  val statusService: StatusService
  implicit val executionContext: ExecutionContext

  def statusRoutes: server.Route =
    pathPrefix("status") {
      pathEndOrSingleSlash {
        get {
          completeWithTrace({ traceContext =>
            statusService.getStatus().map { statusResponse =>
              val httpStatus = if (statusResponse.ok) StatusCodes.OK else StatusCodes.InternalServerError
              (httpStatus, statusResponse)
            }
          })
        }
      }
    } ~
      pathPrefix("version") {
        pathEndOrSingleSlash {
          get {
            completeWithTrace({ traceContext =>
              (StatusCodes.OK, BuildTimeVersion.versionJson)
            })
          }
        }
      }
}
