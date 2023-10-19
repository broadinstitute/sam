package org.broadinstitute.dsde.workbench.sam.model.api

import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport.WorkbenchUserIdFormat
import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import spray.json.DefaultJsonProtocol.jsonFormat2
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

object SamUserAttributes {
  implicit val SamUserAttributesFormat: RootJsonFormat[SamUserAttributes] = jsonFormat2(SamUserAttributes.apply)

}
final case class SamUserAttributes(id: WorkbenchUserId, marketingConsent: Boolean)
