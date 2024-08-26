package org.broadinstitute.dsde.workbench.sam
package model.api

import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport.WorkbenchUserIdFormat
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchUserId}
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

object SamUserAttributesRequest {
  implicit val SamUserAttributesRequestFormat: RootJsonFormat[SamUserAttributesRequest] = jsonFormat14(SamUserAttributesRequest.apply)

}
case class SamUserAttributesRequest(
    userId: WorkbenchUserId,
    marketingConsent: Option[Boolean],
    firstName: Option[String],
    lastName: Option[String],
    organization: Option[String],
    contactEmail: Option[String],
    title: Option[String],
    department: Option[String],
    interestInTerra: Option[List[String]],
    programLocationCity: Option[String],
    programLocationState: Option[String],
    programLocationCountry: Option[String],
    researchArea: Option[List[String]],
    additionalAttributes: Option[String]
) {
  def validateForNewUser: Option[Seq[ErrorReport]] = Option(
    Seq(
      validateMarketingConsentExists
    ).flatten
  ).filter(_.nonEmpty)

  private def validateMarketingConsentExists: Option[ErrorReport] =
    if (marketingConsent.isEmpty) {
      Option(ErrorReport("A new user must provide a acceptance or denial to marketing consent"))
    } else None
}
