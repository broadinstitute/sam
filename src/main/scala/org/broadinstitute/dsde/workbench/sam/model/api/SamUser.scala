package org.broadinstitute.dsde.workbench.sam.model.api

import org.broadinstitute.dsde.workbench.model.{AzureB2CId, GoogleSubjectId, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.model.UserIdInfo

import java.time.Instant

object SamUser {
  def apply(
      id: WorkbenchUserId,
      googleSubjectId: Option[GoogleSubjectId],
      email: WorkbenchEmail,
      azureB2CId: Option[AzureB2CId],
      enabled: Boolean
  ): SamUser =
    SamUser(id, googleSubjectId, email, azureB2CId, enabled, Instant.EPOCH, None, Instant.EPOCH)
}

final case class SamUser(
    id: WorkbenchUserId,
    googleSubjectId: Option[GoogleSubjectId],
    email: WorkbenchEmail,
    azureB2CId: Option[AzureB2CId],
    enabled: Boolean,
    createdAt: Instant,
    registeredAt: Option[Instant],
    updatedAt: Instant
) {
  def toUserIdInfo = UserIdInfo(id, email, googleSubjectId)

  override def equals(other: Any): Boolean = other match {
    case user: SamUser =>
      this.id == user.id &&
      this.googleSubjectId == user.googleSubjectId &&
      this.email == user.email &&
      this.azureB2CId == user.azureB2CId &&
      this.enabled == user.enabled
    case _ => false
  }
}
