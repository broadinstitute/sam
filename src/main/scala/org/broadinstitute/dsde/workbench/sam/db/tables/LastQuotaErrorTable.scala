package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.sam.db.{DatabaseKey, SamTypeBinders}
import scalikejdbc._

import java.time.Instant

final case class LastQuotaErrorPK(value: Long) extends DatabaseKey
final case class LastQuotaErrorRecord(id: LastQuotaErrorPK, lastQuotaError: Instant)

object LastQuotaErrorTable extends SQLSyntaxSupportWithDefaultSamDB[LastQuotaErrorRecord] {
  override def tableName: String = "SAM_LAST_QUOTA_ERROR"

  import SamTypeBinders._
  def apply(e: ResultName[LastQuotaErrorRecord])(rs: WrappedResultSet): LastQuotaErrorRecord = LastQuotaErrorRecord(
    rs.get(e.id),
    rs.timestamp(e.lastQuotaError).toInstant
  )

  def apply(o: SyntaxProvider[LastQuotaErrorRecord])(rs: WrappedResultSet): LastQuotaErrorRecord = apply(o.resultName)(rs)

  def opt(m: SyntaxProvider[LastQuotaErrorRecord])(rs: WrappedResultSet): Option[LastQuotaErrorRecord] =
    rs.longOpt(m.resultName.id).map(_ => LastQuotaErrorTable(m)(rs))
}
