package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.sam.db.DatabaseId
import org.broadinstitute.dsde.workbench.sam.model.ValueObject
import scalikejdbc._

final case class ResourceActionId(value: Long) extends DatabaseId
final case class ResourceActionName(value: String) extends ValueObject
final case class ResourceActionRecord(id: ResourceActionId,
                                      resourceTypeId: ResourceTypeId,
                                      action: ResourceActionName)

object ResourceActionRecord extends SQLSyntaxSupport[ResourceActionRecord] {
  override def tableName: String = "SAM_RESOURCE_ACTION"

  import ResourceTypeRecordBinders._
  import ResourceActionRecordBinders._
  def apply(e: ResultName[ResourceActionRecord])(rs: WrappedResultSet): ResourceActionRecord = ResourceActionRecord(
    rs.get(e.id),
    rs.get(e.resourceTypeId),
    rs.get(e.action)
  )
}

object ResourceActionRecordBinders {
  implicit val resourceActionIdTypeBinder: TypeBinder[ResourceActionId] = new TypeBinder[ResourceActionId] {
    def apply(rs: ResultSet, label: String): ResourceActionId = ResourceActionId(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): ResourceActionId = ResourceActionId(rs.getLong(index))
  }

  implicit val resourceActionNameTypeBinder: TypeBinder[ResourceActionName] = new TypeBinder[ResourceActionName] {
    def apply(rs: ResultSet, label: String): ResourceActionName = ResourceActionName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ResourceActionName = ResourceActionName(rs.getString(index))
  }
}
