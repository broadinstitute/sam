package org.broadinstitute.dsde.workbench.sam.model.api

import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

object SamUserAllowances {
  implicit val SamUserAllowedResponseFormat: RootJsonFormat[SamUserAllowances] = jsonFormat2(SamUserAllowances.apply)

  def apply(allowed: Boolean, enabledInDatabase: Boolean, termsOfService: Boolean): SamUserAllowances =
    SamUserAllowances(allowed, Seq("database" -> enabledInDatabase, "termsOfService" -> termsOfService))
}
final case class SamUserAllowances(
    allowed: Boolean,
    details: Seq[(String, Boolean)]
)
