package org.broadinstitute.dsde.workbench.sam.model.api

import akka.http.scaladsl.model.StatusCodes
import monocle.macros.Lenses
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model.{AzureB2CId, ErrorReport, GoogleSubjectId}
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

object AdminUpdateUserRequest {
  implicit val UserUpdateRequestFormat: RootJsonFormat[AdminUpdateUserRequest] =
    jsonFormat(AdminUpdateUserRequest.apply, "azureB2CId", "googleSubjectId")

  def apply(
      azureB2CId: Option[AzureB2CId],
      googleSubjectId: Option[GoogleSubjectId]
  ): AdminUpdateUserRequest =
    AdminUpdateUserRequest(azureB2CId, googleSubjectId)
}
@Lenses final case class AdminUpdateUserRequest(
    azureB2CId: Option[AzureB2CId],
    googleSubjectId: Option[GoogleSubjectId]
) {
  def isValid(user: SamUser): Seq[ErrorReport] = {
    import org.broadinstitute.dsde.workbench.google.errorReportSource

    var errorReports = Seq[ErrorReport]()
    if (azureB2CId.contains(AzureB2CId("null")) && user.googleSubjectId.isEmpty)
      errorReports = errorReports ++ Seq(ErrorReport(StatusCodes.BadRequest, "Unable to null azureB2CId when the user's googleSubjectId is already null"))
    else if (googleSubjectId.contains(GoogleSubjectId("null")) && user.azureB2CId.isEmpty)
      errorReports = errorReports ++ Seq(ErrorReport(StatusCodes.BadRequest, "Unable to null googleSubjectId when the user's azureB2CId is already null"))
    errorReports
  }
}
