package org.broadinstitute.dsde.workbench.sam
package model.api

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport.WorkbenchUserIdFormat
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat
import org.broadinstitute.dsde.workbench.model.google.GoogleModelJsonSupport.InstantFormat

import java.time.Instant

object SamUserAttributes {
  implicit val SamUserAttributesFormat: RootJsonFormat[SamUserAttributes] = jsonFormat16(SamUserAttributes.apply)

  def newUserAttributesFromRequest(userId: WorkbenchUserId, userAttributesRequest: SamUserAttributesRequest): IO[SamUserAttributes] = {
    val samUserAttributes = for {
      marketingConsent <- userAttributesRequest.marketingConsent
      firstName = userAttributesRequest.firstName
      lastName = userAttributesRequest.lastName
      organization = userAttributesRequest.organization
      contactEmail = userAttributesRequest.contactEmail
      title = userAttributesRequest.title
      department = userAttributesRequest.department
      interestInTerra = userAttributesRequest.interestInTerra
      programLocationCity = userAttributesRequest.programLocationCity
      programLocationState = userAttributesRequest.programLocationState
      programLocationCountry = userAttributesRequest.programLocationCountry
      researchArea = userAttributesRequest.researchArea
      additionalAttributes = userAttributesRequest.additionalAttributes

    } yield SamUserAttributes(
      userId,
      marketingConsent,
      firstName,
      lastName,
      organization,
      contactEmail,
      title,
      department,
      interestInTerra,
      programLocationCity,
      programLocationState,
      programLocationCountry,
      researchArea,
      additionalAttributes,
      Some(Instant.now()),
      Some(Instant.now())
    )
    IO.fromOption(samUserAttributes)(
      new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"Missing values required for new user attributes."))
    )

  }

}
final case class SamUserAttributes(
    userId: WorkbenchUserId,
    marketingConsent: Boolean,
    firstName: Option[String],
    lastName: Option[String],
    organization: Option[String],
    contactEmail: Option[String],
    title: Option[String],
    department: Option[String],
    interestInTerra: Option[List[String]],
    programLocationCity: Option[String],
    programLocationState: Option[String],
    programLocationCountry: Option[String],
    researchArea: Option[List[String]],
    additionalAttributes: Option[String],
    createdAt: Option[Instant],
    updatedAt: Option[Instant]
) {
  def updateFromUserAttributesRequest(userId: WorkbenchUserId, userAttributesRequest: SamUserAttributesRequest): IO[SamUserAttributes] =
    IO(
      this.copy(
        userId = userId,
        marketingConsent = userAttributesRequest.marketingConsent.getOrElse(this.marketingConsent),
        firstName = userAttributesRequest.firstName.orElse(this.firstName),
        lastName = userAttributesRequest.lastName.orElse(this.lastName),
        organization = userAttributesRequest.organization.orElse(this.organization),
        contactEmail = userAttributesRequest.contactEmail.orElse(this.contactEmail),
        title = userAttributesRequest.title.orElse(this.title),
        department = userAttributesRequest.department.orElse(this.department),
        interestInTerra = userAttributesRequest.interestInTerra.orElse(this.interestInTerra),
        programLocationCity = userAttributesRequest.programLocationCity.orElse(this.programLocationCity),
        programLocationState = userAttributesRequest.programLocationState.orElse(this.programLocationState),
        programLocationCountry = userAttributesRequest.programLocationCountry.orElse(this.programLocationCountry),
        researchArea = userAttributesRequest.researchArea.orElse(this.researchArea),
        additionalAttributes = userAttributesRequest.additionalAttributes.orElse(this.additionalAttributes),
      )
    )
}
