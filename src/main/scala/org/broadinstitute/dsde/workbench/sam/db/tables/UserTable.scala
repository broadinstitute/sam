package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders
import org.broadinstitute.dsde.workbench.sam.model.SamUser
import scalikejdbc._

final case class UserRecord(
    id: WorkbenchUserId,
    email: WorkbenchEmail,
    googleSubjectId: Option[GoogleSubjectId],
    enabled: Boolean,
    azureB2cId: Option[AzureB2CId],
    acceptedTosVersion: Option[String],
    auth0Id: Option[Auth0Id]
)

object UserTable extends SQLSyntaxSupportWithDefaultSamDB[UserRecord] {
  override def tableName: String = "SAM_USER"

  import SamTypeBinders._
  def apply(e: ResultName[UserRecord])(rs: WrappedResultSet): UserRecord = UserRecord(
    rs.get(e.id),
    rs.get(e.email),
    rs.stringOpt(e.googleSubjectId).map(GoogleSubjectId),
    rs.get(e.enabled),
    rs.stringOpt(e.azureB2cId).map(AzureB2CId),
    rs.stringOpt(e.acceptedTosVersion),
    rs.stringOpt(e.auth0Id).map(Auth0Id)
  )

  def apply(o: SyntaxProvider[UserRecord])(rs: WrappedResultSet): UserRecord = apply(o.resultName)(rs)

  def unmarshalUserRecord(userRecord: UserRecord): SamUser =
    SamUser(
      userRecord.id,
      userRecord.googleSubjectId,
      userRecord.email,
      userRecord.azureB2cId,
      userRecord.enabled,
      userRecord.acceptedTosVersion,
      userRecord.auth0Id
    )
}
