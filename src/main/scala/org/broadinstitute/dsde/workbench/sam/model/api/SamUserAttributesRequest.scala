package org.broadinstitute.dsde.workbench.sam
package model.api

import org.broadinstitute.dsde.workbench.model.ErrorReport
import spray.json.DefaultJsonProtocol.jsonFormat1
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

object SamUserAttributesRequest {
  implicit val SamUserAttributesRequestFormat: RootJsonFormat[SamUserAttributesRequest] = jsonFormat1(SamUserAttributesRequest.apply)

}
case class SamUserAttributesRequest(
    marketingConsent: Option[Boolean]
) {
  def validateForNewUser: Option[Seq[ErrorReport]] = Option(
    Seq(
      validateMarketingConsentExists
    ).flatten
  ).filter(_.nonEmpty)

  private def validateMarketingConsentExists: Option[ErrorReport] =
    if (marketingConsent.isEmpty) {
      Option(ErrorReport("A new user MUST provide a acceptance or denial to marketing consent"))
    } else None
}
