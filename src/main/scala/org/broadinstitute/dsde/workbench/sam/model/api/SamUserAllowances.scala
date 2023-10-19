package org.broadinstitute.dsde.workbench.sam.model.api

import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

object SamUserAllowances {
  implicit val SamUserAllowedResponseFormat: RootJsonFormat[SamUserAllowances] = jsonFormat2(SamUserAllowances.apply)

  def apply(allowed: Boolean, enabled: Boolean, termsOfService: Boolean): SamUserAllowances =
    SamUserAllowances(allowed, Map("enabled" -> enabled, "termsOfService" -> termsOfService))
}
final case class SamUserAllowances(
    allowed: Boolean,
    details: Map[String, Boolean]
) {

  def getEnabled: Boolean = details.get("enabled").exists(identity)
  def getTermsOfServiceCompliance: Boolean = details.get("termsOfService").exists(identity)

}
