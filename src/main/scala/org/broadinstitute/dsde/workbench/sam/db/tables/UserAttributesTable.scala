package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders
import org.broadinstitute.dsde.workbench.sam.model.SamUserTos
import org.broadinstitute.dsde.workbench.sam.model.api.SamUserAttributes
import scalikejdbc._

import java.time.Instant

final case class UserAttributesRecord(
    samUserId: WorkbenchUserId,
    marketingConsent: Boolean,
    updatedAt: Instant
)

object UserAttributesTable extends SQLSyntaxSupportWithDefaultSamDB[UserAttributesRecord] {
  override def tableName: String = "SAM_USER_ATTRIBUTES"

  import SamTypeBinders._
  def apply(e: ResultName[UserAttributesRecord])(rs: WrappedResultSet): UserAttributesRecord = UserAttributesRecord(
    rs.get(e.samUserId),
    rs.get(e.marketingConsent),
    rs.get(e.updatedAt)
  )

  def apply(o: SyntaxProvider[UserAttributesRecord])(rs: WrappedResultSet): UserAttributesRecord = apply(o.resultName)(rs)

  def unmarshalUserAttributesRecord(userAttributesRecord: UserAttributesRecord): SamUserAttributes =
    SamUserAttributes(
      userAttributesRecord.samUserId,
      userAttributesRecord.marketingConsent
    )
}
