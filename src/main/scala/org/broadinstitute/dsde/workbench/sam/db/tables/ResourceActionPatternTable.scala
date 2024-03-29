package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model.ValueObject
import org.broadinstitute.dsde.workbench.sam.db.{DatabaseKey, SamTypeBinders}
import scalikejdbc._

final case class ResourceActionPatternPK(value: Long) extends DatabaseKey
final case class ResourceActionPatternName(value: String) extends ValueObject
final case class ResourceActionPatternRecord(
    id: ResourceActionPatternPK,
    resourceTypeId: ResourceTypePK,
    actionPattern: ResourceActionPatternName,
    description: String,
    isAuthDomainConstrainable: Boolean
)

object ResourceActionPatternTable extends SQLSyntaxSupportWithDefaultSamDB[ResourceActionPatternRecord] {
  override def tableName: String = "SAM_RESOURCE_TYPE_ACTION_PATTERN"

  import SamTypeBinders._
  def apply(e: ResultName[ResourceActionPatternRecord])(rs: WrappedResultSet): ResourceActionPatternRecord = ResourceActionPatternRecord(
    rs.get(e.id),
    rs.get(e.resourceTypeId),
    rs.get(e.actionPattern),
    rs.get(e.description),
    rs.get(e.isAuthDomainConstrainable)
  )
}
