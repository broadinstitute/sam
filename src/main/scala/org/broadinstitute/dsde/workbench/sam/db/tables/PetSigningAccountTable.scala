package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders
import scalikejdbc._

final case class PetSigningAccountRecord(
    samUserId: WorkbenchUserId,
    project: GoogleProject,
    googleSubjectId: ServiceAccountSubjectId,
    email: WorkbenchEmail,
    displayName: ServiceAccountDisplayName
)

object PetSigningAccountTable extends SQLSyntaxSupportWithDefaultSamDB[PetSigningAccountRecord] {
  override def tableName: String = "SAM_PET_SIGNING_ACCOUNT"

  import SamTypeBinders._
  def apply(e: ResultName[PetSigningAccountRecord])(rs: WrappedResultSet): PetSigningAccountRecord = PetSigningAccountRecord(
    rs.get(e.samUserId),
    rs.get(e.project),
    rs.get(e.googleSubjectId),
    rs.get(e.email),
    rs.get(e.displayName)
  )

  def apply(p: SyntaxProvider[PetSigningAccountRecord])(rs: WrappedResultSet): PetSigningAccountRecord = apply(p.resultName)(rs)
}
