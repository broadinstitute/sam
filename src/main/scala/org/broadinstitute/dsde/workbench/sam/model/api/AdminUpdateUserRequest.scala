package org.broadinstitute.dsde.workbench.sam.model.api

import akka.http.scaladsl.model.StatusCodes
import monocle.macros.Lenses
import org.broadinstitute.dsde.workbench.google.errorReportSource
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model.{AzureB2CId, ErrorReport, GoogleSubjectId}
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

import java.util.regex.Pattern

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
  val UUID_REGEX =
    Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
  private def validateAzureB2CId(azureB2CId: AzureB2CId): Option[ErrorReport] =
    if (!UUID_REGEX.matcher(azureB2CId.value).matches() && !(azureB2CId.value == "null")) {
      Option(ErrorReport(s"invalid azureB2CId [${azureB2CId.value}]"))
    } else None
  def isValid(user: SamUser): Seq[ErrorReport] = {

    var errorReports = Seq[ErrorReport]()
    azureB2CId.foreach(azureB2CId => errorReports = errorReports ++ validateAzureB2CId(azureB2CId))
    if (azureB2CId.contains(AzureB2CId("null")) && user.googleSubjectId.isEmpty)
      errorReports = errorReports ++ Seq(ErrorReport(StatusCodes.BadRequest, "Unable to null azureB2CId when the user's googleSubjectId is already null"))
    else if (googleSubjectId.contains(GoogleSubjectId("null")) && user.azureB2CId.isEmpty)
      errorReports = errorReports ++ Seq(ErrorReport(StatusCodes.BadRequest, "Unable to null googleSubjectId when the user's azureB2CId is already null"))
    errorReports
  }
}
