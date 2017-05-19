package org.broadinstitute.dsde.workbench.sam

import org.broadinstitute.dsde.workbench.sam.model.ErrorReport

/**
  * Created by dvoet on 5/18/17.
  */
class WorkbenchException(message: String = null, cause: Throwable = null) extends Exception(message, cause)

class WorkbenchExceptionWithErrorReport(val errorReport: ErrorReport) extends WorkbenchException(errorReport.toString)
