package org.broadinstitute.dsde.workbench.sam.api

import scala.concurrent.ExecutionContext

trait TermsOfServiceRoutes extends UserInfoDirectives with SamRequestContextDirectives {
  implicit val executionContext: ExecutionContext
  val tosService: TosService

  def tosRoutes: server.Route =
    pathPrefix("tos") {
      pathPrefix("text") {
        pathEndOrSingleSlash {
          get {
            complete {
              tosService.getStatus().map { statusResponse =>
                val httpStatus = if (statusResponse.ok) StatusCodes.OK else StatusCodes.InternalServerError
                (httpStatus, statusResponse)
              }
            }
          }
        }
      }
    }

}
