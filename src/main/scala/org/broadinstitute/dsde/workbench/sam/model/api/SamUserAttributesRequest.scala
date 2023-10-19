package org.broadinstitute.dsde.workbench.sam.model.api

import spray.json.DefaultJsonProtocol.jsonFormat1
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

object SamUserAttributesRequest {
  implicit val SamUserAttributesRequestFormat: RootJsonFormat[SamUserAttributesRequest] = jsonFormat1(SamUserAttributesRequest.apply)

}
case class SamUserAttributesRequest(
    marketingConsent: Option[Boolean]
)
