package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders
import scalikejdbc._

final case class UserRecord(id: WorkbenchUserId,
                            email: WorkbenchEmail,
                            googleSubjectId: Option[GoogleSubjectId],
                            enabled: Boolean,
                            identityConcentratorId: Option[IdentityConcentratorId])

object UserTable extends SQLSyntaxSupportWithDefaultSamDB[UserRecord] {
  override def tableName: String = "SAM_USER"

  import SamTypeBinders._
  def apply(e: ResultName[UserRecord])(rs: WrappedResultSet): UserRecord = UserRecord(
    rs.get(e.id),
    rs.get(e.email),
    rs.stringOpt(e.googleSubjectId).map(GoogleSubjectId),
    rs.get(e.enabled),
    rs.stringOpt(e.identityConcentratorId).map(IdentityConcentratorId)
  )

  def apply(o: SyntaxProvider[UserRecord])(rs: WrappedResultSet): UserRecord = apply(o.resultName)(rs)

  def unmarshalUserRecord(userRecord: UserRecord): WorkbenchUser = {
    WorkbenchUser(userRecord.id, userRecord.googleSubjectId, userRecord.email, userRecord.identityConcentratorId)
  }
}
