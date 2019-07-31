package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.sam.db.{DatabaseKey, SamTypeBinders}
import scalikejdbc._

final case class AccessInstructionsPK(value: Long) extends DatabaseKey
final case class AccessInstructionsRecord(id: AccessInstructionsPK,
                                          groupId: GroupPK,
                                          instructions: String)

object AccessInstructionsTable extends SQLSyntaxSupport[AccessInstructionsRecord] {
  override def tableName: String = "SAM_ACCESS_INSTRUCTIONS"

  import SamTypeBinders._
  def apply(e: ResultName[AccessInstructionsRecord])(rs: WrappedResultSet): AccessInstructionsRecord = AccessInstructionsRecord(
    rs.get(e.id),
    rs.get(e.groupId),
    rs.get(e.instructions)
  )

  def apply(o: SyntaxProvider[AccessInstructionsRecord])(rs: WrappedResultSet): AccessInstructionsRecord = apply(o.resultName)(rs)
}
