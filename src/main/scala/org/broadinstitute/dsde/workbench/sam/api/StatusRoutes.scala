package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.sam.service.StatusService
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import io.opentelemetry.api.GlobalOpenTelemetry
import spray.json.{JsObject, JsString}
import org.broadinstitute.dsde.workbench.util.health.StatusJsonSupport._

import scala.concurrent.ExecutionContext

object BuildTimeVersion {
  val version = Option(getClass.getPackage.getImplementationVersion)
  val versionJson = JsObject(Map("version" -> JsString(version.getOrElse("n/a"))))
}

trait StatusRoutes {
  val statusService: StatusService
  implicit val executionContext: ExecutionContext

  private lazy val checkStatusSuccessCounter = GlobalOpenTelemetry.getMeter("StatusRoutes").counterBuilder("checkStatus-success").build()
  private lazy val checkStatusFailureCounter = GlobalOpenTelemetry.getMeter("StatusRoutes").counterBuilder("checkStatus-failure").build()

  def statusRoutes: server.Route =
    pathPrefix("status") {
      pathEndOrSingleSlash {
        get {
          complete {
            statusService.getStatus().map { statusResponse =>
              val httpStatus = if (statusResponse.ok) {
                checkStatusSuccessCounter.add(1)
                StatusCodes.OK
              } else {
                checkStatusFailureCounter.add(1)
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
