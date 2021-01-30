package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders._
import org.broadinstitute.dsde.workbench.sam.db.{DatabaseArray, DatabaseKey}
import scalikejdbc._

final case class GroupMemberFlatPK(value: Long) extends DatabaseKey

final case class GroupMembershipPath(path: List[GroupPK]) extends DatabaseArray {
  override val baseTypeName: String = "BIGINT"
  override def asJavaArray: Array[Object] = path.toArray.map(_.value.asInstanceOf[Object])

  def append(memberToAdd: GroupPK): GroupMembershipPath = GroupMembershipPath(this.path :+ memberToAdd)
  def append(that: GroupMembershipPath): GroupMembershipPath = GroupMembershipPath(this.path ++ that.path)
}

// lastGroupMembershipElement is added so it can be indexed as a performance optimization, it is always the last element of groupMembershipPath
final case class GroupMemberFlatRecord(id: GroupMemberFlatPK,
                                       groupId: GroupPK,
                                       memberUserId: Option[WorkbenchUserId],
                                       memberGroupId: Option[GroupPK],
                                       groupMembershipPath: GroupMembershipPath,
                                       lastGroupMembershipElement: Option[GroupPK])
object GroupMemberFlatTable extends SQLSyntaxSupportWithDefaultSamDB[GroupMemberFlatRecord] {
  override def tableName: String = "SAM_GROUP_MEMBER_FLAT"

  def apply(e: ResultName[GroupMemberFlatRecord])(rs: WrappedResultSet): GroupMemberFlatRecord = GroupMemberFlatRecord(
    rs.get(e.id),
    rs.get(e.groupId),
    rs.stringOpt(e.memberUserId).map(WorkbenchUserId),
    rs.longOpt(e.memberGroupId).map(GroupPK),
    rs.get(e.groupMembershipPath),
    rs.longOpt(e.memberGroupId).map(GroupPK),
  )

  def apply(m: SyntaxProvider[GroupMemberFlatRecord])(rs: WrappedResultSet): GroupMemberFlatRecord = apply(m.resultName)(rs)

  def opt(m: SyntaxProvider[GroupMemberFlatRecord])(rs: WrappedResultSet): Option[GroupMemberFlatRecord] =
    rs.longOpt(m.resultName.id).map(_ => GroupMemberFlatTable(m)(rs))
}
