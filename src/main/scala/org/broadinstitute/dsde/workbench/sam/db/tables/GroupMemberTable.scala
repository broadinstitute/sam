package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.db.{DatabaseKey, SamTypeBinders}
import scalikejdbc._

final case class GroupMemberPK(value: Long) extends DatabaseKey
final case class GroupMemberRecord(id: GroupMemberPK, groupId: GroupPK, memberUserId: Option[WorkbenchUserId], memberGroupId: Option[GroupPK])

object GroupMemberTable extends SQLSyntaxSupportWithDefaultSamDB[GroupMemberRecord] {
  override def tableName: String = "SAM_GROUP_MEMBER"

  import SamTypeBinders._
  def apply(e: ResultName[GroupMemberRecord])(rs: WrappedResultSet): GroupMemberRecord = GroupMemberRecord(
    rs.get(e.id),
    rs.get(e.groupId),
    rs.stringOpt(e.memberUserId).map(WorkbenchUserId),
    rs.longOpt(e.memberGroupId).map(GroupPK)
  )

  def apply(m: SyntaxProvider[GroupMemberRecord])(rs: WrappedResultSet): GroupMemberRecord = apply(m.resultName)(rs)

  def opt(m: SyntaxProvider[GroupMemberRecord])(rs: WrappedResultSet): Option[GroupMemberRecord] =
    rs.longOpt(m.resultName.id).map(_ => GroupMemberTable(m)(rs))
}
