package org.broadinstitute.dsde.workbench.sam.model.api

import org.broadinstitute.dsde.workbench.sam.model.TermsOfServiceDetails
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat
import org.broadinstitute.dsde.workbench.sam.model.api.SamUserAllowances.SamUserAllowedResponseFormat
import org.broadinstitute.dsde.workbench.sam.model.api.SamUserAttributes.SamUserAttributesFormat
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._

object SamUserCombinedStateResponse {
  implicit val SamUserResponseFormat: RootJsonFormat[SamUserCombinedStateResponse] = jsonFormat3(SamUserCombinedStateResponse.apply)
}
final case class SamUserCombinedStateResponse(
    allowances: SamUserAllowances,
    attributes: SamUserAttributes,
    termsOfServiceDetails: TermsOfServiceDetails
)
