package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.model.ValueObject
import org.broadinstitute.dsde.workbench.sam.db.DatabaseKey
import scalikejdbc._

final case class ResourceRoleKey(value: Long) extends DatabaseKey
final case class ResourceRoleName(value: String) extends ValueObject
final case class ResourceRoleRecord(id: ResourceRoleKey,
                                    resourceTypeId: ResourceTypeKey,
                                    role: ResourceRoleName)

object ResourceRoleTable extends SQLSyntaxSupport[ResourceRoleRecord] {
  override def tableName: String = "SAM_RESOURCE_ROLE"

  import ResourceTypeTableBinders._
  import ResourceRoleTableBinders._
  def apply(e: ResultName[ResourceRoleRecord])(rs: WrappedResultSet): ResourceRoleRecord = ResourceRoleRecord(
    rs.get(e.id),
    rs.get(e.resourceTypeId),
    rs.get(e.role)
  )
}

object ResourceRoleTableBinders {
  implicit val resourceRoleIdTypeBinder: TypeBinder[ResourceRoleKey] = new TypeBinder[ResourceRoleKey] {
    def apply(rs: ResultSet, label: String): ResourceRoleKey = ResourceRoleKey(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): ResourceRoleKey = ResourceRoleKey(rs.getLong(index))
  }

  implicit val resourceRoleNameTypeBinder: TypeBinder[ResourceRoleName] = new TypeBinder[ResourceRoleName] {
    def apply(rs: ResultSet, label: String): ResourceRoleName = ResourceRoleName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ResourceRoleName = ResourceRoleName(rs.getString(index))
  }
}
