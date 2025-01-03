package org.broadinstitute.dsde.workbench.sam.model.api

import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.api.SamUserAllowances.SamUserAllowedResponseFormat
import org.broadinstitute.dsde.workbench.sam.model.api.SamUserAttributes.SamUserAttributesFormat
import org.broadinstitute.dsde.workbench.sam.model.TermsOfServiceDetails
import spray.json.DefaultJsonProtocol._
import spray.json._

object SamUserSupportSummaryResponse {
  implicit val SamUserSupportSummaryResponseFormat: RootJsonFormat[SamUserSupportSummaryResponse] = jsonFormat7(SamUserSupportSummaryResponse.apply)
}
final case class SamUserSupportSummaryResponse(
    samUser: SamUser,
    allowances: SamUserAllowances,
    attributes: Option[SamUserAttributes],
    termsOfServiceDetails: TermsOfServiceDetails,
    additionalDetails: Map[String, JsValue],
    directGroupMembershipCount: Int,
    indirectGroupMembershipCount: Int // TODO: for clarity, should this be "directAndIndirectGroup..."?
)
