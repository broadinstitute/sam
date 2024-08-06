package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders
import org.broadinstitute.dsde.workbench.sam.model.api.SamUserAttributes
import scalikejdbc._
import java.time.Instant

final case class UserAttributesRecord(
    samUserId: WorkbenchUserId,
    marketingConsent: Boolean,
    firstName: Option[String],
    lastName: Option[String],
    organization: Option[String],
    contactEmail: Option[String],
    title: Option[String],
    department: Option[String],
    interestInTerra: Option[Array[String]],
    programLocationCity: Option[String],
    programLocationState: Option[String],
    programLocationCountry: Option[String],
    researchArea: Option[Array[String]],
    additionalAttributes: Option[String],
    createdAt: Instant,
    updatedAt: Instant
)

object UserAttributesTable extends SQLSyntaxSupportWithDefaultSamDB[UserAttributesRecord] {
  override def tableName: String = "SAM_USER_ATTRIBUTES"

  import SamTypeBinders._
  def apply(e: ResultName[UserAttributesRecord])(rs: WrappedResultSet): UserAttributesRecord = UserAttributesRecord(
    rs.get(e.samUserId),
    rs.get(e.marketingConsent),
    rs.get(e.firstName),
    rs.get(e.lastName),
    rs.get(e.organization),
    rs.get(e.contactEmail),
    rs.get(e.title),
    rs.get(e.department),
    rs.get(e.interestInTerra),
    rs.get(e.programLocationCity),
    rs.get(e.programLocationState),
    rs.get(e.programLocationCountry),
    rs.get(e.researchArea),
    rs.get(e.additionalAttributes),
    rs.get(e.createdAt),
    rs.get(e.updatedAt)
  )

  def apply(o: SyntaxProvider[UserAttributesRecord])(rs: WrappedResultSet): UserAttributesRecord = apply(o.resultName)(rs)

  def unmarshalUserAttributesRecord(userAttributesRecord: UserAttributesRecord): SamUserAttributes =
    SamUserAttributes(
      userAttributesRecord.samUserId,
      userAttributesRecord.marketingConsent,
      userAttributesRecord.firstName,
      userAttributesRecord.lastName,
      userAttributesRecord.organization,
      userAttributesRecord.contactEmail,
      userAttributesRecord.title,
      userAttributesRecord.department,
      userAttributesRecord.interestInTerra,
      userAttributesRecord.programLocationCity,
      userAttributesRecord.programLocationState,
      userAttributesRecord.programLocationCountry,
      userAttributesRecord.researchArea,
      userAttributesRecord.additionalAttributes,
      userAttributesRecord.createdAt,
      userAttributesRecord.updatedAt
  )
}
