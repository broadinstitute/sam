package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.db.{DatabaseKey, SamTypeBinders}
import scalikejdbc._

import java.time.Instant

final case class GroupPK(value: Long) extends DatabaseKey
final case class GroupRecord(id: GroupPK,
                             name: WorkbenchGroupName,
                             email: WorkbenchEmail,
                             updatedDate: Option[Instant],
                             synchronizedDate: Option[Instant])

object GroupTable extends SQLSyntaxSupportWithDefaultSamDB[GroupRecord] {
  override def tableName: String = "SAM_GROUP"

  import SamTypeBinders._
  def apply(e: ResultName[GroupRecord])(rs: WrappedResultSet): GroupRecord = GroupRecord(
    rs.get(e.id),
    rs.get(e.name),
    rs.get(e.email),
    rs.timestampOpt(e.updatedDate).map(_.toInstant),
    rs.timestampOpt(e.synchronizedDate).map(_.toInstant)
  )

  def apply(o: SyntaxProvider[GroupRecord])(rs: WrappedResultSet): GroupRecord = apply(o.resultName)(rs)

  def opt(m: SyntaxProvider[GroupRecord])(rs: WrappedResultSet): Option[GroupRecord] =
    rs.longOpt(m.resultName.id).map(_ => GroupTable(m)(rs))
}
