package org.broadinstitute.dsde.workbench.sam.model.api
import spray.json.JsObject

import java.util.UUID

case class SamLock(
    id: UUID,
    description: String,
    lockType: String,
    lockDetails: JsObject
) {

  override def equals(other: Any): Boolean = other match {
    case lock: SamLock =>
      this.id == lock.id &&
      this.description == lock.description &&
      this.lockType == lock.lockType &&
      this.lockDetails == lock.lockDetails
    case _ => false
  }
}
