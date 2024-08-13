package org.broadinstitute.dsde.workbench.sam.model.api

import org.broadinstitute.dsde.workbench.model.google.GoogleModelJsonSupport._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount}
import org.broadinstitute.dsde.workbench.sam.model.{ResourceAction, ResourceId}
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

// This should live in wb-libs
object ActionServiceAccountId {
  implicit val ActionServiceAccountIdFormat: RootJsonFormat[ActionServiceAccountId] = jsonFormat3(ActionServiceAccountId.apply)
}
case class ActionServiceAccountId(resourceId: ResourceId, action: ResourceAction, project: GoogleProject)

object ActionServiceAccount {
  implicit val ActionServiceAccountFormat: RootJsonFormat[ActionServiceAccount] = jsonFormat2(ActionServiceAccount.apply)
}
case class ActionServiceAccount(id: ActionServiceAccountId, serviceAccount: ServiceAccount)
