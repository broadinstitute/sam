package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.model.ValueObject
import org.broadinstitute.dsde.workbench.sam.db.DatabaseId
import scalikejdbc._

final case class ResourceActionPatternId(value: Long) extends DatabaseId
final case class ResourceActionPattern(value: String) extends ValueObject
final case class ResourceActionPatternRecord(id: ResourceActionPatternId,
                                             resourceTypeId: ResourceTypeId,
                                             actionPattern: ResourceActionPattern)

object ResourceActionPatternTable extends SQLSyntaxSupport[ResourceActionPatternRecord] {
  override def tableName: String = "SAM_ACTION_PATTERN"

  import ResourceTypeTableBinders._
  import ResourceActionPatternTableBinders._
  def apply(e: ResultName[ResourceActionPatternRecord])(rs: WrappedResultSet): ResourceActionPatternRecord = ResourceActionPatternRecord(
    rs.get(e.id),
    rs.get(e.resourceTypeId),
    rs.get(e.actionPattern)
  )
}

object ResourceActionPatternTableBinders {
  implicit val resourceActionPatternIdTypeBinder: TypeBinder[ResourceActionPatternId] = new TypeBinder[ResourceActionPatternId] {
    def apply(rs: ResultSet, label: String): ResourceActionPatternId = ResourceActionPatternId(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): ResourceActionPatternId = ResourceActionPatternId(rs.getLong(index))
  }

  implicit val actionPatternTypeBinder: TypeBinder[ResourceActionPattern] = new TypeBinder[ResourceActionPattern] {
    def apply(rs: ResultSet, label: String): ResourceActionPattern = ResourceActionPattern(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ResourceActionPattern = ResourceActionPattern(rs.getString(index))
  }
}
