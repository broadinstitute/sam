package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.sam.db.{DatabaseKey, SamTypeBinders}
import org.broadinstitute.dsde.workbench.sam.model.ResourceRoleName
import scalikejdbc._

final case class ResourceRolePK(value: Long) extends DatabaseKey
final case class ResourceRoleRecord(id: ResourceRolePK,
                                    resourceTypeId: ResourceTypePK,
                                    role: ResourceRoleName)

object ResourceRoleTable extends SQLSyntaxSupportWithDefaultSamDB[ResourceRoleRecord] {
  override def tableName: String = "SAM_RESOURCE_ROLE"

  import SamTypeBinders._
  def apply(e: ResultName[ResourceRoleRecord])(rs: WrappedResultSet): ResourceRoleRecord = ResourceRoleRecord(
    rs.get(e.id),
    rs.get(e.resourceTypeId),
    rs.get(e.role)
  )
}
