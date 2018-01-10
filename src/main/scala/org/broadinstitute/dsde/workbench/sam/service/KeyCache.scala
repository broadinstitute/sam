package org.broadinstitute.dsde.workbench.sam.service

import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKey, ServiceAccountKeyId, ServiceAccountPrivateKeyData}
import org.broadinstitute.dsde.workbench.model.{WorkbenchUser, WorkbenchUserId}

import scala.concurrent.Future

/**
  * Created by mbemis on 1/10/18.
  */
trait KeyCache {
  def onBoot(): Future[Unit]
  def getKey(user: WorkbenchUser, project: GoogleProject): Future[ServiceAccountKey]
  def removeKey(userId: WorkbenchUserId, project: GoogleProject, keyId: ServiceAccountKeyId): Future[Unit]
}

trait NoKeyCache extends KeyCache {
  override def onBoot(): Future[Unit] = Future.successful(())
  override def getKey(user: WorkbenchUser, project: GoogleProject): Future[ServiceAccountKey] = Future.successful(ServiceAccountKey(ServiceAccountKeyId("foo"), ServiceAccountPrivateKeyData("bar"), None, None))
  override def removeKey(userId: WorkbenchUserId, project: GoogleProject, keyId: ServiceAccountKeyId): Future[Unit] = Future.successful(())
}

object NoKeyCache extends NoKeyCache