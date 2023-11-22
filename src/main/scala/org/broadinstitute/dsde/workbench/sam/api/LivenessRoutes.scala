package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes.{OK, ServiceUnavailable}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import io.opentelemetry.api.OpenTelemetry
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO

class LivenessRoutes(directoryDAO: DirectoryDAO) extends SamRequestContextDirectives {
  override lazy val otel: OpenTelemetry = OpenTelemetry.noop()
  val route: server.Route =
    withSamRequestContext { samRequestContext =>
      pathPrefix("liveness") {
        pathEndOrSingleSlash {
          get {
            complete {
              directoryDAO.checkStatus(samRequestContext).map {
                case true => OK
                case false => ServiceUnavailable
              }
            }
          }
        }
      }
    }
}
