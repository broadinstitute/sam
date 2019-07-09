package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._

final case class RoleActionRecord(roleId: ResourceRoleId,
                                  actionId: ResourceActionId)

object RoleActionTable extends SQLSyntaxSupport[RoleActionRecord] {
  override def tableName: String = "SAM_ROLE_ACTION"

  import ResourceActionTableBinders._
  import ResourceRoleTableBinders._
  def apply(e: ResultName[RoleActionRecord])(rs: WrappedResultSet): RoleActionRecord = RoleActionRecord(
    rs.get(e.roleId),
    rs.get(e.actionId)
  )
}
