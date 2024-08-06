package org.broadinstitute.dsde.workbench.sam
package model.api

import org.broadinstitute.dsde.workbench.model.ErrorReport
import spray.json.DefaultJsonProtocol.jsonFormat15
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat
import org.broadinstitute.dsde.workbench.model.google.GoogleModelJsonSupport.InstantFormat
import java.time.Instant

object SamUserAttributesRequest {
  implicit val SamUserAttributesRequestFormat: RootJsonFormat[SamUserAttributesRequest] = jsonFormat15(SamUserAttributesRequest.apply)

}
case class SamUserAttributesRequest(
    marketingConsent: Option[Boolean],
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
