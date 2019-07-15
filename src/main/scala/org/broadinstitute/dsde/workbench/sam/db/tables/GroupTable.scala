package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet
import java.time.Instant

import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.db.DatabaseKey
import scalikejdbc._

final case class GroupKey(value: Long) extends DatabaseKey
final case class GroupRecord(id: GroupKey,
                             name: WorkbenchGroupName,
                             email: WorkbenchEmail,
                             updatedDate: Option[Instant],
                             synchronizedDate: Option[Instant])

object GroupTable extends SQLSyntaxSupport[GroupRecord] {
  override def tableName: String = "SAM_GROUP"

  import GroupTableBinders._
  def apply(e: ResultName[GroupRecord])(rs: WrappedResultSet): GroupRecord = GroupRecord(
    rs.get(e.id),
    rs.get(e.name),
    rs.get(e.email),
    rs.get(e.updatedDate),
    rs.get(e.synchronizedDate)
  )

  def apply(o: SyntaxProvider[GroupRecord])(rs: WrappedResultSet): GroupRecord = apply(o.resultName)(rs)

  def opt(m: SyntaxProvider[GroupRecord])(rs: WrappedResultSet): Option[GroupRecord] =
    rs.longOpt(m.resultName.id).map(_ => GroupTable(m)(rs))
}

object GroupTableBinders {
  implicit val groupIdTypeBinder: TypeBinder[GroupKey] = new TypeBinder[GroupKey] {
    def apply(rs: ResultSet, label: String): GroupKey = GroupKey(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): GroupKey = GroupKey(rs.getLong(index))
  }

  implicit val groupNameTypeBinder: TypeBinder[WorkbenchGroupName] = new TypeBinder[WorkbenchGroupName] {
    def apply(rs: ResultSet, label: String): WorkbenchGroupName = WorkbenchGroupName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): WorkbenchGroupName = WorkbenchGroupName(rs.getString(index))
  }

  implicit val groupEmailTypeBinder: TypeBinder[WorkbenchEmail] = new TypeBinder[WorkbenchEmail] {
    def apply(rs: ResultSet, label: String): WorkbenchEmail = WorkbenchEmail(rs.getString(label))
    def apply(rs: ResultSet, index: Int): WorkbenchEmail = WorkbenchEmail(rs.getString(index))
  }
}
