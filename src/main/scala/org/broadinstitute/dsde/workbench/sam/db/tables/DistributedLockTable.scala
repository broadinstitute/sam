package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._

import java.time.Instant

final case class DistributedLockRecord(lockName: String, lockValue: String, expiresAt: Instant)

object DistributedLockTable extends SQLSyntaxSupportWithDefaultSamDB[DistributedLockRecord] {
  override def tableName: String = "DISTRIBUTED_LOCK"

  def apply(resultName: ResultName[DistributedLockRecord])(rs: WrappedResultSet): DistributedLockRecord = DistributedLockRecord(
    rs.get(resultName.lockName),
    rs.get(resultName.lockValue),
    rs.get(resultName.expiresAt)
  )

  def apply(resultSyntax: SyntaxProvider[DistributedLockRecord])(rs: WrappedResultSet): DistributedLockRecord = apply(resultSyntax.resultName)(rs)
}
