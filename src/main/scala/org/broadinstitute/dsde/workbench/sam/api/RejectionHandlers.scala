package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.{MalformedRequestContentRejection, RejectionHandler, RequestEntityExpectedRejection}
import org.broadinstitute.dsde.workbench.model.ErrorReport
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport.ErrorReportFormat
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

object RejectionHandlers {

  def termsOfServiceRejectionHandler(tosUrl: String) = RejectionHandler.newBuilder().handle {
    case RequestEntityExpectedRejection | MalformedRequestContentRejection(_, _) =>
      complete(StatusCodes.Forbidden, ErrorReport(StatusCodes.Forbidden, s"You must accept the Terms of Service in order to register. See ${tosUrl}"))
  }.result()

}
