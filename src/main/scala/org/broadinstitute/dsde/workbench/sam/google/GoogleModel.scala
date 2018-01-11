package org.broadinstitute.dsde.workbench.sam.google

import java.time.Instant

import org.broadinstitute.dsde.workbench.model.google.{ServiceAccountKey, ServiceAccountKeyId, ServiceAccountPrivateKeyData}
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail}
import spray.json.DefaultJsonProtocol

object GoogleModelJsonSupport {
  import DefaultJsonProtocol._
  import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._
  import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
  import org.broadinstitute.dsde.workbench.model.google.GoogleModelJsonSupport._

  implicit val SyncReportItemFormat = jsonFormat3(SyncReportItem)
  implicit val ServiceAccountKeyWithEmailFormat = jsonFormat5(ServiceAccountKeyWithEmail)
}

case class SyncReportItem(operation: String, email: String, errorReport: Option[ErrorReport])

case class ServiceAccountKeyWithEmail(email: WorkbenchEmail, id: ServiceAccountKeyId, privateKeyData: ServiceAccountPrivateKeyData, validAfter: Option[Instant], validBefore: Option[Instant]) {
  def this(email: WorkbenchEmail, serviceAccountKey: ServiceAccountKey) = this(email, serviceAccountKey.id, serviceAccountKey.privateKeyData, serviceAccountKey.validAfter, serviceAccountKey.validBefore)
}