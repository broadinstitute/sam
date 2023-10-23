package org.broadinstitute.dsde.workbench.sam.model.api

import org.broadinstitute.dsde.workbench.model.ErrorReport
import spray.json.DefaultJsonProtocol.jsonFormat1
import spray.json.RootJsonFormat

object SamUserRegistrationRequest {
  implicit val SamUserRegistrationRequestFormat: RootJsonFormat[SamUserRegistrationRequest] = jsonFormat1(SamUserRegistrationRequest.apply)
}
case class SamUserRegistrationRequest(
    userAttributes: SamUserAttributesRequest
) {

  def validateForNewUser: Option[Seq[ErrorReport]] = Option(
    Seq(
      userAttributes.validateForNewUser.getOrElse(Seq.empty)
    ).flatten
  ).filter(_.nonEmpty)
}
