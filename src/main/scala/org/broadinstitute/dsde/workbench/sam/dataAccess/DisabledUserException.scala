package org.broadinstitute.dsde.workbench.sam.dataAccess

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.errorReportSource

class DisabledUserException extends
  WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Unauthorized, "user is disabled"))
