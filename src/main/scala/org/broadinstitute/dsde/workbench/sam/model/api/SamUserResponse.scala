package org.broadinstitute.dsde.workbench.sam.model.api

import org.broadinstitute.dsde.workbench.model.{AzureB2CId, GoogleSubjectId, WorkbenchEmail, WorkbenchUserId}
import spray.json.DefaultJsonProtocol.jsonFormat8
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model.google.GoogleModelJsonSupport.InstantFormat
import spray.json.RootJsonFormat

import java.time.Instant

object SamUserResponse {

  implicit val SamUserResponseFormat: RootJsonFormat[SamUserResponse] = jsonFormat8(SamUserResponse.apply)

  def apply(samUser: SamUser, allowed: Boolean): SamUserResponse =
    SamUserResponse(
      samUser.id,
      samUser.googleSubjectId,
      samUser.email,
      samUser.azureB2CId,
      allowed = allowed,
      samUser.createdAt,
      samUser.registeredAt,
      samUser.updatedAt
    )
}
final case class SamUserResponse(
    id: WorkbenchUserId,
    googleSubjectId: Option[GoogleSubjectId],
    email: WorkbenchEmail,
    azureB2CId: Option[AzureB2CId],
    allowed: Boolean,
    createdAt: Instant,
    registeredAt: Option[Instant],
    updatedAt: Instant
) {}
