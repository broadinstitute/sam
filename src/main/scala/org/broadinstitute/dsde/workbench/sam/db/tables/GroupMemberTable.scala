package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.sam.db.DatabaseId
import scalikejdbc._

final case class GroupMemberId(value: Long) extends DatabaseId
final case class GroupMemberRecord(id: GroupMemberId,
                                   groupId: GroupId,
                                   memberUserId: Option[UserId],
                                   memberGroupId: Option[GroupId])

object GroupMemberRecord extends SQLSyntaxSupport[GroupMemberRecord] {
  override def tableName: String = "SAM_GROUP_MEMBER"

  import GroupMemberRecordBinders._
  import UserRecordBinders._
  def apply(e: ResultName[GroupMemberRecord])(rs: WrappedResultSet): GroupMemberRecord = GroupMemberRecord(
    rs.get(e.id),
    rs.get(e.groupId),
    rs.get(e.memberUserId),
    rs.get(e.memberGroupId)
  )
}

object GroupMemberRecordBinders {
  implicit val groupMemberIdTypeBinder: TypeBinder[GroupMemberId] = new TypeBinder[GroupMemberId] {
    def apply(rs: ResultSet, label: String): GroupMemberId = GroupMemberId(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): GroupMemberId = GroupMemberId(rs.getLong(index))
  }
}
