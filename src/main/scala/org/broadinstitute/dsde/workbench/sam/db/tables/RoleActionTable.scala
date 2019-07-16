package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders

final case class RoleActionRecord(roleId: ResourceRolePK,
                                  actionId: ResourceActionPK)

object RoleActionTable extends SQLSyntaxSupport[RoleActionRecord] {
  override def tableName: String = "SAM_ROLE_ACTION"

  import SamTypeBinders._
  def apply(e: ResultName[RoleActionRecord])(rs: WrappedResultSet): RoleActionRecord = RoleActionRecord(
    rs.get(e.roleId),
    rs.get(e.actionId)
  )
}
