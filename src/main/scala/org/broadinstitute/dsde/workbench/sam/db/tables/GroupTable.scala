package org.broadinstitute.dsde.workbench.sam.db.tables

import java.time.Instant

import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.db.{DatabaseKey, SamTypeBinders}
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory.SqlInterpolationWithSamBinders
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
    rs.timestampOpt(e.updatedDate).map(_.toInstant),
    rs.timestampOpt(e.synchronizedDate).map(_.toInstant)
  )

  def apply(o: SyntaxProvider[GroupRecord])(rs: WrappedResultSet): GroupRecord = apply(o.resultName)(rs)

  def opt(m: SyntaxProvider[GroupRecord])(rs: WrappedResultSet): Option[GroupRecord] =
    rs.longOpt(m.resultName.id).map(_ => GroupTable(m)(rs))

  def groupPKQueryForGroup(groupName: WorkbenchGroupName, groupTableAlias: String = "gpk"): SQLSyntax = {
    val gpk = GroupTable.syntax(groupTableAlias)
    samsqls"select ${gpk.id} from ${GroupTable as gpk} where ${gpk.name} = $groupName"
  }
}
