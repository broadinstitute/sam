package org.broadinstitute.dsde.workbench.sam.google

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{ErrorReport, ErrorReportSource}
import spray.json.DefaultJsonProtocol

object SamGoogleModelJsonSupport {
  import DefaultJsonProtocol._
  import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._

  implicit val SyncReportItemFormat = jsonFormat3(SyncReportItem.apply)
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
final case class SyncReportItem(operation: String, email: String, errorReport: Option[ErrorReport])

object SyncReportItem {
  def fromIO[T](operation: String, email: String, result: IO[T])(implicit errorReportSource: ErrorReportSource): IO[SyncReportItem] =
    result.redeem(t => SyncReportItem(operation, email, Option(ErrorReport(t))), _ => SyncReportItem(operation, email, None))
}
