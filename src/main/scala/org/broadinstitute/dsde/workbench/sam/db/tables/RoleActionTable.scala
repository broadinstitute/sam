package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders

final case class RoleActionRecord(resourceRoleId: ResourceRolePK, resourceActionId: ResourceActionPK)

object RoleActionTable extends SQLSyntaxSupportWithDefaultSamDB[RoleActionRecord] {
  override def tableName: String = "SAM_ROLE_ACTION"

  import SamTypeBinders._
  def apply(e: ResultName[RoleActionRecord])(rs: WrappedResultSet): RoleActionRecord = RoleActionRecord(
    rs.get(e.resourceRoleId),
    rs.get(e.resourceActionId)
  )
}
