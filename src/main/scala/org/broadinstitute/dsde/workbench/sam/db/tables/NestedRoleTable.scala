package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders

final case class NestedRoleRecord(baseRoleId: ResourceRolePK,
                                  nestedRoleId: ResourceRolePK,
                                  descendantsOnly: Boolean)

object NestedRoleTable extends SQLSyntaxSupportWithDefaultSamDB[NestedRoleRecord] {
  override def tableName: String = "SAM_NESTED_ROLE"

  import SamTypeBinders._
  def apply(e: ResultName[NestedRoleRecord])(rs: WrappedResultSet): NestedRoleRecord = NestedRoleRecord(
    rs.get(e.baseRoleId),
    rs.get(e.nestedRoleId),
    rs.get(e.descendantsOnly)
  )
}
