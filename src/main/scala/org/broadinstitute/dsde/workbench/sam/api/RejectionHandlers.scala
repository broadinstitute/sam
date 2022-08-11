package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.{MalformedRequestContentRejection, Rejection, RejectionHandler, RequestEntityExpectedRejection}
import org.broadinstitute.dsde.workbench.model.ErrorReport
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport.ErrorReportFormat
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._


object RejectionHandlers {

  def termsOfServiceRejectionHandler(tosUrl: String) = RejectionHandler.newBuilder().handle {
    case RequestEntityExpectedRejection =>
      complete(StatusCodes.Forbidden, ErrorReport(StatusCodes.Forbidden, s"You must accept the Terms of Service in order to register. See $tosUrl"))
    case MalformedRequestContentRejection(y, x) =>
      complete(StatusCodes.Forbidden, ErrorReport(StatusCodes.Forbidden, s"You must accept the Terms of Service in order to register. See $tosUrl"))
    case MethodDisabled(message) =>
      complete(StatusCodes.Forbidden, ErrorReport(StatusCodes.Forbidden, message))
  }.result()

  final case class MethodDisabled(message: String) extends Rejection


}
