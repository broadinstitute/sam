package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.sam.db.DatabaseId
import org.broadinstitute.dsde.workbench.sam.model.ValueObject
import scalikejdbc._

final case class ResourceId(value: Long) extends DatabaseId
final case class ResourceName(value: String) extends ValueObject
final case class ResourceRecord(id: ResourceId,
                                name: ResourceName,
                                resourceTypeId: ResourceTypeId)

object ResourceRecord extends SQLSyntaxSupport[ResourceRecord] {
  override def tableName: String = "SAM_RESOURCE"

  import ResourceTypeRecordBinders._
  import ResourceRecordBinders._
  def apply(e: ResultName[ResourceRecord])(rs: WrappedResultSet): ResourceRecord = ResourceRecord(
    rs.get(e.id),
    rs.get(e.name),
    rs.get(e.resourceTypeId)
  )
}

object ResourceRecordBinders {
  implicit val resourceIdTypeBinder: TypeBinder[ResourceId] = new TypeBinder[ResourceId] {
    def apply(rs: ResultSet, label: String): ResourceId = ResourceId(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): ResourceId = ResourceId(rs.getLong(index))
  }

  implicit val resourceNameTypeBinder: TypeBinder[ResourceName] = new TypeBinder[ResourceName] {
    def apply(rs: ResultSet, label: String): ResourceName = ResourceName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ResourceName = ResourceName(rs.getString(index))
  }
}
