package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders
import scalikejdbc._

final case class PetServiceAccountRecord(
    userId: WorkbenchUserId,
    project: GoogleProject,
    googleSubjectId: ServiceAccountSubjectId,
    email: WorkbenchEmail,
    displayName: ServiceAccountDisplayName
)

object PetServiceAccountTable extends SQLSyntaxSupportWithDefaultSamDB[PetServiceAccountRecord] {
  override def tableName: String = "SAM_PET_SERVICE_ACCOUNT"

  import SamTypeBinders._
  def apply(e: ResultName[PetServiceAccountRecord])(rs: WrappedResultSet): PetServiceAccountRecord = PetServiceAccountRecord(
    rs.get(e.userId),
    rs.get(e.project),
    rs.get(e.googleSubjectId),
    rs.get(e.email),
    rs.get(e.displayName)
  )

  def apply(p: SyntaxProvider[PetServiceAccountRecord])(rs: WrappedResultSet): PetServiceAccountRecord = apply(p.resultName)(rs)
}
