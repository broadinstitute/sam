package org.broadinstitute.dsde.workbench.sam
package model.api

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport.WorkbenchUserIdFormat
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import spray.json.DefaultJsonProtocol.jsonFormat2
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

object SamUserAttributes {
  implicit val SamUserAttributesFormat: RootJsonFormat[SamUserAttributes] = jsonFormat2(SamUserAttributes.apply)

  def newUserAttributesFromRequest(userId: WorkbenchUserId, userAttributesRequest: SamUserAttributesRequest): IO[SamUserAttributes] = {
    val samUserAttributes = for {
      marketingConsent <- userAttributesRequest.marketingConsent
    } yield SamUserAttributes(userId, marketingConsent)
    IO.fromOption(samUserAttributes)(
      new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"Missing values required for new user attributes."))
    )

  }

}
final case class SamUserAttributes(userId: WorkbenchUserId, marketingConsent: Boolean) {
  def updateFromUserAttributesRequest(userAttributesRequest: SamUserAttributesRequest): IO[SamUserAttributes] =
    IO(this.copy(marketingConsent = userAttributesRequest.marketingConsent.getOrElse(this.marketingConsent)))
}
