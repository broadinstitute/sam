package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.model.ValueObject
import org.broadinstitute.dsde.workbench.sam.db.DatabaseKey
import scalikejdbc._

final case class ResourceKey(value: Long) extends DatabaseKey
final case class ResourceName(value: String) extends ValueObject
final case class ResourceRecord(id: ResourceKey,
                                name: ResourceName,
                                resourceTypeId: ResourceTypeKey)

object ResourceTable extends SQLSyntaxSupport[ResourceRecord] {
  override def tableName: String = "SAM_RESOURCE"

  import ResourceTypeTableBinders._
  import ResourceTableBinders._
  def apply(e: ResultName[ResourceRecord])(rs: WrappedResultSet): ResourceRecord = ResourceRecord(
    rs.get(e.id),
    rs.get(e.name),
    rs.get(e.resourceTypeId)
  )
}

object ResourceTableBinders {
  implicit val resourceIdTypeBinder: TypeBinder[ResourceKey] = new TypeBinder[ResourceKey] {
    def apply(rs: ResultSet, label: String): ResourceKey = ResourceKey(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): ResourceKey = ResourceKey(rs.getLong(index))
  }

  implicit val resourceNameTypeBinder: TypeBinder[ResourceName] = new TypeBinder[ResourceName] {
    def apply(rs: ResultSet, label: String): ResourceName = ResourceName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ResourceName = ResourceName(rs.getString(index))
  }
}
