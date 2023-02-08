package org.broadinstitute.dsde.workbench.sam.google

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{ErrorReport, ErrorReportSource}
import spray.json.DefaultJsonProtocol

object SamGoogleModelJsonSupport {
  import DefaultJsonProtocol._
  import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._

  implicit val SyncReportItemFormat = jsonFormat3(SyncReportItem.apply)
}

final case class SyncReportItem(operation: String, email: String, errorReport: Option[ErrorReport])

object SyncReportItem {
  def fromIO[T](operation: String, email: String, result: IO[T])(implicit errorReportSource: ErrorReportSource): IO[SyncReportItem] =
    result.redeem(t => SyncReportItem(operation, email, Option(ErrorReport(t))), _ => SyncReportItem(operation, email, None))
}
