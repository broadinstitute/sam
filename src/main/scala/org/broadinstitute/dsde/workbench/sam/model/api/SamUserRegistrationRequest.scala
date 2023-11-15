package org.broadinstitute.dsde.workbench.sam
package model.api

import org.broadinstitute.dsde.workbench.model.ErrorReport
import spray.json.DefaultJsonProtocol.jsonFormat2
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

object SamUserRegistrationRequest {
  implicit val SamUserRegistrationRequestFormat: RootJsonFormat[SamUserRegistrationRequest] = jsonFormat2(SamUserRegistrationRequest.apply)
}
case class SamUserRegistrationRequest(
     acceptsTermsOfService: Boolean,
     userAttributes: SamUserAttributesRequest
) {
  def validateForNewUser: Option[Seq[ErrorReport]] = Option(
    Seq(
      validateAcceptsTermsOfService.getOrElse(Seq.empty),
      userAttributes.validateForNewUser.getOrElse(Seq.empty)
    ).flatten
  ).filter(_.nonEmpty)

  private def validateAcceptsTermsOfService: Option[Seq[ErrorReport]] = Option(
    Seq(
      if (!acceptsTermsOfService) {
        Option(ErrorReport("A new user must accept the Terms of Service while registering"))
      } else None
    ).flatten
  ).filter(_.nonEmpty)
}
