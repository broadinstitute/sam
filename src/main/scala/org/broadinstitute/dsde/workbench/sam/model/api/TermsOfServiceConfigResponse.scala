package org.broadinstitute.dsde.workbench.sam.model.api

import monocle.macros.Lenses
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat
case object TermsOfServiceConfigResponse {
  implicit val termsOfServiceResponseFormat: RootJsonFormat[TermsOfServiceConfigResponse] = jsonFormat4(TermsOfServiceConfigResponse.apply)
}

@Lenses final case class TermsOfServiceConfigResponse(enforced: Boolean, currentVersion: String, inGracePeriod: Boolean, inRollingAcceptanceWindow: Boolean)
