package org.broadinstitute.dsde.workbench.sam.db.tables

import java.util.UUID

final case class LockRecord(id: UUID, description: String, lock_type: String, lock_detail: Object)

object LockTable extends SQLSyntaxSupportWithDefaultSamDB[LockRecord] {
  override def tableName: String = "SAM_LOCK"

}
