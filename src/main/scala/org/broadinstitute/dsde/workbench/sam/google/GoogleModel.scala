package org.broadinstitute.dsde.workbench.sam.google

import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail}
import spray.json.DefaultJsonProtocol

object GoogleModelJsonSupport {
  import DefaultJsonProtocol._
  import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._
  import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._

  implicit val SyncReportItemFormat = jsonFormat3(SyncReportItem)
}

case class SyncReportItem(operation: String, email: String, errorReport: Option[ErrorReport])
