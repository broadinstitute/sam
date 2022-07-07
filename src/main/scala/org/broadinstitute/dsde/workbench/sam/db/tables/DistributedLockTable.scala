package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._

import java.time.Instant

final case class DistributedLockRecord(lockName: String,
                                       lockValue: String,
                                       expiresAt: Instant)

object DistributedLockTable extends SQLSyntaxSupportWithDefaultSamDB[DistributedLockRecord] {
  override def tableName: String = "DISTRIBUTED_LOCK"

  def apply(e: ResultName[DistributedLockRecord])(rs: WrappedResultSet): DistributedLockRecord = DistributedLockRecord(
    rs.get(e.lockName),
    rs.get(e.lockValue),
    rs.get(e.expiresAt)
  )

  def apply(o: SyntaxProvider[DistributedLockRecord])(rs: WrappedResultSet): DistributedLockRecord = apply(o.resultName)(rs)
}
