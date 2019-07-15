package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.model.ValueObject
import org.broadinstitute.dsde.workbench.sam.db.DatabaseKey
import scalikejdbc._

final case class ResourceTypeKey(value: Long) extends DatabaseKey
final case class ResourceTypeName(value: String) extends ValueObject
final case class ResourceTypeRecord(id: ResourceTypeKey,
                                    resourceTypeName: ResourceTypeName)

object ResourceTypeTable extends SQLSyntaxSupport[ResourceTypeRecord] {
  override def tableName: String = "SAM_RESOURCE_TYPE"

  import ResourceTypeTableBinders._
  def apply(e: ResultName[ResourceTypeRecord])(rs: WrappedResultSet): ResourceTypeRecord = ResourceTypeRecord(
    rs.get(e.id),
    rs.get(e.resourceTypeName)
  )
}

object ResourceTypeTableBinders {
  implicit val resourceTypeIdTypeBinder: TypeBinder[ResourceTypeKey] = new TypeBinder[ResourceTypeKey] {
    def apply(rs: ResultSet, label: String): ResourceTypeKey = ResourceTypeKey(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): ResourceTypeKey = ResourceTypeKey(rs.getLong(index))
  }

  implicit val resourceTypeNameTypeBinder: TypeBinder[ResourceTypeName] = new TypeBinder[ResourceTypeName] {
    def apply(rs: ResultSet, label: String): ResourceTypeName = ResourceTypeName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ResourceTypeName = ResourceTypeName(rs.getString(index))
  }
}
