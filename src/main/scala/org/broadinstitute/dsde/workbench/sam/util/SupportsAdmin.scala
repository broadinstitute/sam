package org.broadinstitute.dsde.workbench.sam.util

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import org.broadinstitute.dsde.workbench.google.errorReportSource
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions

trait SupportsAdmin {
  val cloudExtensions: CloudExtensions
  def ensureAdminIfNeeded[A](userId: WorkbenchUserId, samRequestContext: SamRequestContext)(func: => IO[A]): IO[A] = {
    val unauthorized = new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Unauthorized, "You are not allowed to make this request"))
    samRequestContext.samUser match {
      case Some(requestingUser) =>
        if (userId != requestingUser.id) {
          IO.fromFuture(IO(cloudExtensions.isWorkbenchAdmin(requestingUser.email))).flatMap {
            case true => func
            case false => IO.raiseError(unauthorized)
          }
        } else {
          func
        }
      case None => IO.raiseError(unauthorized)
    }
  }
}
