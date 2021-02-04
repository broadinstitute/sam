package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.sam.db.{DatabaseKey, SamTypeBinders}
import org.broadinstitute.dsde.workbench.sam.model.ResourceId
import scalikejdbc._

final case class ResourcePK(value: Long) extends DatabaseKey
final case class ResourceRecord(id: ResourcePK,
                                name: ResourceId,
                                resourceTypeId: ResourceTypePK,
                                resourceParentId: Option[ResourcePK])

object ResourceTable extends SQLSyntaxSupportWithDefaultSamDB[ResourceRecord] {
  override def tableName: String = "SAM_RESOURCE"

  import SamTypeBinders._
  def apply(e: ResultName[ResourceRecord])(rs: WrappedResultSet): ResourceRecord = ResourceRecord(
    rs.get(e.id),
    rs.get(e.name),
    rs.get(e.resourceTypeId),
    rs.longOpt(e.resourceParentId).map(ResourcePK)
  )
}
