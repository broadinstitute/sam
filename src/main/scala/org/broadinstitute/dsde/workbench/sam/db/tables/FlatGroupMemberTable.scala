package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders._
import org.broadinstitute.dsde.workbench.sam.db.{DatabaseArray, DatabaseKey}
import scalikejdbc._

final case class FlatGroupMemberPK(value: Long) extends DatabaseKey

// note: this is the variant which does not include member in path
final case class FlatGroupMembershipPath(path: List[GroupPK]) extends DatabaseArray {
  override val baseTypeName: String = "BIGINT"
  override def asJavaArray: Array[Object] = path.toArray.map(_.value.asInstanceOf[Object])

  def append(memberToAdd: GroupPK): FlatGroupMembershipPath = FlatGroupMembershipPath(this.path :+ memberToAdd)
  def append(that: FlatGroupMembershipPath): FlatGroupMembershipPath = FlatGroupMembershipPath(this.path ++ that.path)
}

// lastGroupMembershipElement is added so it can be indexed as a performance optimization, it is always the last element of groupMembershipPath
final case class FlatGroupMemberRecord(id: FlatGroupMemberPK,
                                       groupId: GroupPK,
                                       memberUserId: Option[WorkbenchUserId],
                                       memberGroupId: Option[GroupPK],
                                       groupMembershipPath: FlatGroupMembershipPath,
                                       lastGroupMembershipElement: Option[GroupPK])
object FlatGroupMemberTable extends SQLSyntaxSupportWithDefaultSamDB[FlatGroupMemberRecord] {
  override def tableName: String = "SAM_GROUP_MEMBER_FLAT"

  def apply(e: ResultName[FlatGroupMemberRecord])(rs: WrappedResultSet): FlatGroupMemberRecord = FlatGroupMemberRecord(
    rs.get(e.id),
    rs.get(e.groupId),
    rs.stringOpt(e.memberUserId).map(WorkbenchUserId),
    rs.longOpt(e.memberGroupId).map(GroupPK),
    rs.get(e.groupMembershipPath),
    rs.longOpt(e.memberGroupId).map(GroupPK),
  )

  def apply(m: SyntaxProvider[FlatGroupMemberRecord])(rs: WrappedResultSet): FlatGroupMemberRecord = apply(m.resultName)(rs)

  def opt(m: SyntaxProvider[FlatGroupMemberRecord])(rs: WrappedResultSet): Option[FlatGroupMemberRecord] =
    rs.longOpt(m.resultName.id).map(_ => FlatGroupMemberTable(m)(rs))
}
