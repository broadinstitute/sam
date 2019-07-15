package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.model.ValueObject
import org.broadinstitute.dsde.workbench.sam.db.DatabaseKey
import scalikejdbc._

final case class ResourceActionKey(value: Long) extends DatabaseKey
final case class ResourceActionName(value: String) extends ValueObject
final case class ResourceActionRecord(id: ResourceActionKey,
                                      resourceTypeId: ResourceTypeKey,
                                      action: ResourceActionName)

object ResourceActionTable extends SQLSyntaxSupport[ResourceActionRecord] {
  override def tableName: String = "SAM_RESOURCE_ACTION"

  import ResourceTypeTableBinders._
  import ResourceActionTableBinders._
  def apply(e: ResultName[ResourceActionRecord])(rs: WrappedResultSet): ResourceActionRecord = ResourceActionRecord(
    rs.get(e.id),
    rs.get(e.resourceTypeId),
    rs.get(e.action)
  )
}

object ResourceActionTableBinders {
  implicit val resourceActionIdTypeBinder: TypeBinder[ResourceActionKey] = new TypeBinder[ResourceActionKey] {
    def apply(rs: ResultSet, label: String): ResourceActionKey = ResourceActionKey(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): ResourceActionKey = ResourceActionKey(rs.getLong(index))
  }

  implicit val resourceActionNameTypeBinder: TypeBinder[ResourceActionName] = new TypeBinder[ResourceActionName] {
    def apply(rs: ResultSet, label: String): ResourceActionName = ResourceActionName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ResourceActionName = ResourceActionName(rs.getString(index))
  }
}
