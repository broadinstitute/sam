package org.broadinstitute.dsde.workbench.sam.google

import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchGroupEmail}
import spray.json.DefaultJsonProtocol

object GoogleModelJsonSupport {
  import DefaultJsonProtocol._
  import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._
  import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._

  implicit val SyncReportItemFormat = jsonFormat3(SyncReportItem)
  implicit val SyncReportFormat = jsonFormat2(SyncReport)
}

case class SyncReportItem(operation: String, email: String, errorReport: Option[ErrorReport])
case class SyncReport(groupEmail: WorkbenchGroupEmail, items: Seq[SyncReportItem])
