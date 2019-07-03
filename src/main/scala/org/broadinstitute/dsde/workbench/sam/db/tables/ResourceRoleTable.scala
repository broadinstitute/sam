package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.sam.db.DatabaseId
import org.broadinstitute.dsde.workbench.sam.model.ValueObject
import scalikejdbc._

final case class ResourceRoleId(value: Long) extends DatabaseId
final case class ResourceRoleName(value: String) extends ValueObject
final case class ResourceRoleRecord(id: ResourceRoleId,
                                    resourceTypeId: ResourceTypeId,
                                    role: ResourceRoleName)

object ResourceRoleRecord extends SQLSyntaxSupport[ResourceRoleRecord] {
  override def tableName: String = "SAM_RESOURCE_ROLE"

  import ResourceTypeRecordBinders._
  import ResourceRoleRecordBinders._
  def apply(e: ResultName[ResourceRoleRecord])(rs: WrappedResultSet): ResourceRoleRecord = ResourceRoleRecord(
    rs.get(e.id),
    rs.get(e.resourceTypeId),
    rs.get(e.role)
  )
}

object ResourceRoleRecordBinders {
  implicit val resourceRoleIdTypeBinder: TypeBinder[ResourceRoleId] = new TypeBinder[ResourceRoleId] {
    def apply(rs: ResultSet, label: String): ResourceRoleId = ResourceRoleId(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): ResourceRoleId = ResourceRoleId(rs.getLong(index))
  }

  implicit val resourceRoleNameTypeBinder: TypeBinder[ResourceRoleName] = new TypeBinder[ResourceRoleName] {
    def apply(rs: ResultSet, label: String): ResourceRoleName = ResourceRoleName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ResourceRoleName = ResourceRoleName(rs.getString(index))
  }
}
