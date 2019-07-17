package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.{GoogleSubjectId, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders
import scalikejdbc._

final case class PetServiceAccountRecord(userId: WorkbenchUserId,
                                         project: GoogleProject,
                                         googleSubjectId: GoogleSubjectId,
                                         email: WorkbenchEmail)

object PetServiceAccountTable extends SQLSyntaxSupport[PetServiceAccountRecord] {
  override def tableName: String = "SAM_PET_SERVICE_ACCOUNT"

  import SamTypeBinders._
  def apply(e: ResultName[PetServiceAccountRecord])(rs: WrappedResultSet): PetServiceAccountRecord = PetServiceAccountRecord(
    rs.get(e.userId),
    rs.get(e.project),
    rs.get(e.googleSubjectId),
    rs.get(e.email)
  )
}
