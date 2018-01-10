package org.broadinstitute.dsde.workbench.sam.keycache

import org.broadinstitute.dsde.workbench.model.{WorkbenchUser, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKey, ServiceAccountKeyId}

import scala.concurrent.Future

/**
  * Created by mbemis on 1/10/18.
  */
trait KeyCache {

  def getKey(user: WorkbenchUser, project: GoogleProject): Future[ServiceAccountKey]
  def removeKey(userId: WorkbenchUserId, project: GoogleProject, keyId: ServiceAccountKeyId): Future[Unit]

}
