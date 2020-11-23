package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.db.{DatabaseKey, SamTypeBinders}
import scalikejdbc._

final case class FlatGroupMemberPK(value: Long) extends DatabaseKey
final case class FlatGroupMembershipPath(value: Seq[GroupPK]) // note: variant which does not include member in path
final case class FlatGroupMemberRecord(id: FlatGroupMemberPK,
                                       groupId: GroupPK,
                                       memberUserId: Option[WorkbenchUserId],
                                       memberGroupId: Option[GroupPK],
                                       groupMembershipPath: FlatGroupMembershipPath)
// TODO move to SamTypeBinders?
object TempTypeBinders {
  implicit val flatGroupMemberPKTypeBinder: TypeBinder[FlatGroupMemberPK] = new TypeBinder[FlatGroupMemberPK] {
    def apply(rs: ResultSet, label: String): FlatGroupMemberPK = FlatGroupMemberPK(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): FlatGroupMemberPK = FlatGroupMemberPK(rs.getLong(index))
  }

  implicit val flatGroupMembershipPathPKTypeBinder: TypeBinder[FlatGroupMembershipPath] = new TypeBinder[FlatGroupMembershipPath] {
    def apply(rs: ResultSet, label: String): FlatGroupMembershipPath = FlatGroupMembershipPath(rs.getArray(label).getArray.asInstanceOf[Array[Long]].map(GroupPK).toSeq)
    def apply(rs: ResultSet, index: Int): FlatGroupMembershipPath = FlatGroupMembershipPath(rs.getArray(index).getArray.asInstanceOf[Array[Long]].map(GroupPK).toSeq)
  }
}

object FlatGroupMemberTable extends SQLSyntaxSupportWithDefaultSamDB[FlatGroupMemberRecord] {
  override def tableName: String = "SAM_GROUP_MEMBER_FLAT"

  import TempTypeBinders._
  import SamTypeBinders._
  def apply(e: ResultName[FlatGroupMemberRecord])(rs: WrappedResultSet): FlatGroupMemberRecord = FlatGroupMemberRecord(
    rs.get(e.id),
    rs.get(e.groupId),
    rs.stringOpt(e.memberUserId).map(WorkbenchUserId),
    rs.longOpt(e.memberGroupId).map(GroupPK),
    rs.get(e.groupMembershipPath),
  )

  def apply(m: SyntaxProvider[FlatGroupMemberRecord])(rs: WrappedResultSet): FlatGroupMemberRecord = apply(m.resultName)(rs)

  def opt(m: SyntaxProvider[FlatGroupMemberRecord])(rs: WrappedResultSet): Option[FlatGroupMemberRecord] =
    rs.longOpt(m.resultName.id).map(_ => FlatGroupMemberTable(m)(rs))
}
