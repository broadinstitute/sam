package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.sam.db.{DatabaseKey, SamTypeBinders}
import org.broadinstitute.dsde.workbench.sam.model.ResourceAction
import scalikejdbc._

final case class ResourceActionPK(value: Long) extends DatabaseKey
final case class ResourceActionRecord(id: ResourceActionPK,
                                      resourceTypeId: ResourceTypePK,
                                      action: ResourceAction)

object ResourceActionTable extends SQLSyntaxSupport[ResourceActionRecord] {
  override def tableName: String = "SAM_RESOURCE_ACTION"

  import SamTypeBinders._
  def apply(e: ResultName[ResourceActionRecord])(rs: WrappedResultSet): ResourceActionRecord = ResourceActionRecord(
    rs.get(e.id),
    rs.get(e.resourceTypeId),
    rs.get(e.action)
  )
}
