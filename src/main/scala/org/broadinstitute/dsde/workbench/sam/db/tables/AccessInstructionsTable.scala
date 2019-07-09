package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.sam.db.DatabaseId
import scalikejdbc._

final case class AccessInstructionsId(value: Long) extends DatabaseId
final case class AccessInstructionsRecord(id: AccessInstructionsId,
                                          groupId: GroupId,
                                          instructions: String)

object AccessInstructionsTable extends SQLSyntaxSupport[AccessInstructionsRecord] {
  override def tableName: String = "SAM_ACCESS_INSTRUCTIONS"

  import AccessInstructionsTableBinders._
  import GroupTableBinders._
  def apply(e: ResultName[AccessInstructionsRecord])(rs: WrappedResultSet): AccessInstructionsRecord = AccessInstructionsRecord(
    rs.get(e.id),
    rs.get(e.groupId),
    rs.get(e.instructions)
  )
}

object AccessInstructionsTableBinders {
  implicit val accessInstructionsIdTypeBinder: TypeBinder[AccessInstructionsId] = new TypeBinder[AccessInstructionsId] {
    def apply(rs: ResultSet, label: String): AccessInstructionsId = AccessInstructionsId(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): AccessInstructionsId = AccessInstructionsId(rs.getLong(index))
  }
}
