package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.sam.db.DatabaseKey
import scalikejdbc._

final case class AccessInstructionsKey(value: Long) extends DatabaseKey
final case class AccessInstructionsRecord(id: AccessInstructionsKey,
                                          groupId: GroupKey,
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
  implicit val accessInstructionsIdTypeBinder: TypeBinder[AccessInstructionsKey] = new TypeBinder[AccessInstructionsKey] {
    def apply(rs: ResultSet, label: String): AccessInstructionsKey = AccessInstructionsKey(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): AccessInstructionsKey = AccessInstructionsKey(rs.getLong(index))
  }
}
