package org.broadinstitute.dsde.workbench.sam.db.tables

import java.time.Instant

import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.db.{DatabaseKey, SamTypeBinders}
import scalikejdbc._

final case class GroupPK(value: Long) extends DatabaseKey
final case class GroupRecord(id: GroupPK,
                             name: WorkbenchGroupName,
                             email: WorkbenchEmail,
                             updatedDate: Option[Instant],
                             synchronizedDate: Option[Instant])

object GroupTable extends SQLSyntaxSupport[GroupRecord] {
  override def tableName: String = "SAM_GROUP"

  import SamTypeBinders._
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
