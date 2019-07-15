package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.db.DatabaseKey
import scalikejdbc._

final case class GroupMemberKey(value: Long) extends DatabaseKey
final case class GroupMemberRecord(id: GroupMemberKey,
                                   groupId: GroupKey,
                                   memberUserId: Option[WorkbenchUserId],
                                   memberGroupId: Option[GroupKey])

object GroupMemberTable extends SQLSyntaxSupport[GroupMemberRecord] {
  override def tableName: String = "SAM_GROUP_MEMBER"

  import GroupMemberTableBinders._
  import GroupTableBinders._
  import UserTableBinders._
  def apply(e: ResultName[GroupMemberRecord])(rs: WrappedResultSet): GroupMemberRecord = GroupMemberRecord(
    rs.get(e.id),
    rs.get(e.groupId),
    rs.get(e.memberUserId),
    rs.get(e.memberGroupId)
  )

  def apply(m: SyntaxProvider[GroupMemberRecord])(rs: WrappedResultSet): GroupMemberRecord = apply(m.resultName)(rs)

  def opt(m: SyntaxProvider[GroupMemberRecord])(rs: WrappedResultSet): Option[GroupMemberRecord] =
    rs.longOpt(m.resultName.id).map(_ => GroupMemberTable(m)(rs))
}

object GroupMemberTableBinders {
  implicit val groupMemberIdTypeBinder: TypeBinder[GroupMemberKey] = new TypeBinder[GroupMemberKey] {
    def apply(rs: ResultSet, label: String): GroupMemberKey = GroupMemberKey(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): GroupMemberKey = GroupMemberKey(rs.getLong(index))
  }
}
