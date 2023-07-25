package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes.{OK, ServiceUnavailable}
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import cats.effect.{IO, Resource}
import org.broadinstitute.dsde.workbench.sam.dataAccess.PostgresDirectoryDAO

class LivenessRoutes(postgresDirectoryDAO: Resource[IO, PostgresDirectoryDAO]) extends SamRequestContextDirectives {
  val route: server.Route =
    withSamRequestContext { samRequestContext =>
      pathPrefix("liveness") {
        pathEndOrSingleSlash {
          get {
            complete {
              postgresDirectoryDAO.use(_.checkStatus(samRequestContext).map {
                case true => OK
                case false => ServiceUnavailable
              })
            }
          }
        }
      }
    }
}
