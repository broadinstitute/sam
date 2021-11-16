package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import org.broadinstitute.dsde.workbench.sam.service.TosService

import scala.concurrent.ExecutionContext

trait TermsOfServiceRoutes {
  val tosService: TosService
  implicit val executionContext: ExecutionContext

  def termsOfServiceRoutes: server.Route =
    pathPrefix("tos") {
      path("text") {
        pathEndOrSingleSlash {
          get {
            complete(tosService.getText)
          }
        }
      }
    }
}
