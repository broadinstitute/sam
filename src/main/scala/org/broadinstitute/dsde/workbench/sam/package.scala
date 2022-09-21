package org.broadinstitute.dsde.workbench

import org.broadinstitute.dsde.workbench.model.ErrorReportSource

/** Created by dvoet on 5/18/17.
  */
package object sam {
  implicit val errorReportSource = ErrorReportSource("sam")
}
