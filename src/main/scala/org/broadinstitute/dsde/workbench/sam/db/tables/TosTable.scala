package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders
import org.broadinstitute.dsde.workbench.sam.model.SamUserTos
import scalikejdbc._

import java.time.Instant

final case class TosRecord(
    samUserId: WorkbenchUserId,
    version: String,
    action: String,
    createdAt: Instant
)

object TosTable extends SQLSyntaxSupportWithDefaultSamDB[TosRecord] {
  val ACCEPT = "ACCEPT"
  val REJECT = "REJECT"
  override def tableName: String = "SAM_USER_TERMS_OF_SERVICE"

  import SamTypeBinders._
  def apply(e: ResultName[TosRecord])(rs: WrappedResultSet): TosRecord = TosRecord(
    rs.get(e.samUserId),
    rs.get(e.version),
    rs.get(e.action),
    rs.get(e.createdAt)
  )

  def apply(o: SyntaxProvider[TosRecord])(rs: WrappedResultSet): TosRecord = apply(o.resultName)(rs)

  def unmarshalUserRecord(tosRecord: TosRecord): SamUserTos =
    SamUserTos(
      tosRecord.samUserId,
      tosRecord.version,
      tosRecord.action,
      tosRecord.createdAt
    )
}
