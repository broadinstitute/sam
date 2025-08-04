package org.broadinstitute.dsde.workbench.sam.model.api

import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat
object SamUserAllowances {
  implicit val SamUserAllowedResponseFormat: RootJsonFormat[SamUserAllowances] = jsonFormat2(SamUserAllowances.apply(_: Boolean, _: SamUserAllowancesDetails))

  def apply(enabled: Boolean, termsOfService: Boolean): SamUserAllowances =
    SamUserAllowances(enabled && termsOfService, SamUserAllowancesDetails(enabled, termsOfService))
}
final case class SamUserAllowances(
    allowed: Boolean,
    details: SamUserAllowancesDetails
) {

  def getEnabled: Boolean = details.enabled
  def getTermsOfServiceCompliance: Boolean = details.termsOfService

}

object SamUserAllowancesDetails {
  implicit val SamUserAllowancesDetailsResponseFormat: RootJsonFormat[SamUserAllowancesDetails] = jsonFormat2(SamUserAllowancesDetails.apply)
}
final case class SamUserAllowancesDetails(
    enabled: Boolean,
    termsOfService: Boolean
)
