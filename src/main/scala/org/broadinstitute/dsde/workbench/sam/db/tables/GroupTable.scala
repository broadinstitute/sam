package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet
import java.time.Instant

import org.broadinstitute.dsde.workbench.sam.db.DatabaseId
import org.broadinstitute.dsde.workbench.sam.model.ValueObject
import scalikejdbc._

final case class GroupId(value: Long) extends DatabaseId
final case class GroupName(value: String) extends ValueObject
final case class GroupEmail(value: String) extends ValueObject
final case class GroupRecord(id: GroupId,
                             name: GroupName,
                             email: GroupEmail,
                             updatedDate: Option[Instant],
                             synchronizedDate: Option[Instant])

object GroupRecord extends SQLSyntaxSupport[GroupRecord] {
  override def tableName: String = "SAM_GROUP"

  import GroupRecordBinders._
  def apply(e: ResultName[GroupRecord])(rs: WrappedResultSet): GroupRecord = GroupRecord(
    rs.get(e.id),
    rs.get(e.name),
    rs.get(e.email),
    rs.get(e.updatedDate),
    rs.get(e.synchronizedDate)
  )
}

object GroupRecordBinders {
  implicit val groupIdTypeBinder: TypeBinder[GroupId] = new TypeBinder[GroupId] {
    def apply(rs: ResultSet, label: String): GroupId = GroupId(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): GroupId = GroupId(rs.getLong(index))
  }

  implicit val groupNameTypeBinder: TypeBinder[GroupName] = new TypeBinder[GroupName] {
    def apply(rs: ResultSet, label: String): GroupName = GroupName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): GroupName = GroupName(rs.getString(index))
  }

  implicit val groupEmailTypeBinder: TypeBinder[GroupEmail] = new TypeBinder[GroupEmail] {
    def apply(rs: ResultSet, label: String): GroupEmail = GroupEmail(rs.getString(label))
    def apply(rs: ResultSet, index: Int): GroupEmail = GroupEmail(rs.getString(index))
  }
}
