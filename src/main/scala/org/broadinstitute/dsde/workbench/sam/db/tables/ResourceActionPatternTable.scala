package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.model.ValueObject
import org.broadinstitute.dsde.workbench.sam.db.DatabaseKey
import scalikejdbc._

final case class ResourceActionPatternKey(value: Long) extends DatabaseKey
final case class ResourceActionPatternName(value: String) extends ValueObject
final case class ResourceActionPatternRecord(id: ResourceActionPatternKey,
                                             resourceTypeId: ResourceTypeKey,
                                             actionPattern: ResourceActionPatternName)

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
  implicit val resourceActionPatternIdTypeBinder: TypeBinder[ResourceActionPatternKey] = new TypeBinder[ResourceActionPatternKey] {
    def apply(rs: ResultSet, label: String): ResourceActionPatternKey = ResourceActionPatternKey(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): ResourceActionPatternKey = ResourceActionPatternKey(rs.getLong(index))
  }

  implicit val actionPatternTypeBinder: TypeBinder[ResourceActionPatternName] = new TypeBinder[ResourceActionPatternName] {
    def apply(rs: ResultSet, label: String): ResourceActionPatternName = ResourceActionPatternName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ResourceActionPatternName = ResourceActionPatternName(rs.getString(index))
  }
}
