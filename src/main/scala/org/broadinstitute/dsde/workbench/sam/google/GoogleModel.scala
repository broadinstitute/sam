package org.broadinstitute.dsde.workbench.sam.google

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{ErrorReport, ErrorReportSource, WorkbenchEmail}
import spray.json.DefaultJsonProtocol

object SamGoogleModelJsonSupport {
  import DefaultJsonProtocol._
  import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._

  implicit val SyncReportItemFormat = jsonFormat4(SyncReportItem.apply)
  implicit val SyncReportLogItemFormat = jsonFormat5(SyncReportLogItem.apply)
}

/** A SyncReportItem represents the results of synchronizing a single member of a google group. Synchronizing a google group will result in a collection of
  * these.
  * @param operation
  *   usually either "added" or "removed"
  * @param email
  *   the affected email address
  * @param errorReport
  *   whether or not there was an error
  */
final case class SyncReportItem(operation: String, email: String, workbenchGroupIdentity: String, errorReport: Option[ErrorReport])

object SyncReportItem {
  def fromIO[T](operation: String, email: String, workbenchGroupIdentity: String, result: IO[T])(implicit
      errorReportSource: ErrorReportSource
  ): IO[SyncReportItem] =
    result.redeem(
      t => SyncReportItem(operation, email, workbenchGroupIdentity, Option(ErrorReport(t))),
      _ => SyncReportItem(operation, email, workbenchGroupIdentity, None)
    )
}

object SyncReportLogItem {
  def fromSyncReportItem(group: WorkbenchEmail, syncReportItem: SyncReportItem): SyncReportLogItem =
    SyncReportLogItem(group.value, syncReportItem.operation, syncReportItem.email, syncReportItem.workbenchGroupIdentity, syncReportItem.errorReport)
}
final case class SyncReportLogItem(group: String, operation: String, email: String, workbenchGroupIdentity: String, errorReport: Option[ErrorReport])
