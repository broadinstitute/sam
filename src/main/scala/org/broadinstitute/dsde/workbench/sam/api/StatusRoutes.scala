package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.sam.service.StatusService
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import spray.json.{JsObject, JsString}
import org.broadinstitute.dsde.workbench.util.health.StatusJsonSupport._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics

import scala.concurrent.ExecutionContext

object BuildTimeVersion {
  val version = Option(getClass.getPackage.getImplementationVersion)
  val versionJson = JsObject(Map("version" -> JsString(version.getOrElse("n/a"))))
}

trait StatusRoutes {
  val statusService: StatusService
  private val openTelemetryTags: Map[String, String] = Map("endpoint" -> "status")
  implicit val executionContext: ExecutionContext
  implicit val openTelemetry: OpenTelemetryMetrics[IO]

  def statusRoutes: server.Route =
    pathPrefix("status") {
      pathEndOrSingleSlash {
        get {
          complete {
            throw new RuntimeException("Oh noes!")
            statusService.getStatus().map { statusResponse =>
              val httpStatus = if (statusResponse.ok) {
                openTelemetry.incrementCounter("checkStatus-success", tags = openTelemetryTags).unsafeToFuture()
                StatusCodes.OK
              } else {
                openTelemetry.incrementCounter("checkStatus-failure", tags = openTelemetryTags).unsafeToFuture()
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
