package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders
import scalikejdbc._

final case class EnabledUserRecord(id: WorkbenchUserId)

object EnabledUserTable extends SQLSyntaxSupportWithDefaultSamDB[EnabledUserRecord] {
  override def tableName: String = "SAM_ENABLED_USER"

  import SamTypeBinders._
  def apply(e: ResultName[EnabledUserRecord])(rs: WrappedResultSet): EnabledUserRecord = EnabledUserRecord(rs.get(e.id))
  def apply(o: SyntaxProvider[EnabledUserRecord])(rs: WrappedResultSet): EnabledUserRecord = apply(o.resultName)(rs)
}
