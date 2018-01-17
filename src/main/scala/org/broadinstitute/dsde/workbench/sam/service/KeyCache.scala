package org.broadinstitute.dsde.workbench.sam.service

import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKeyId}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchUser, WorkbenchUserId}

import scala.concurrent.Future

/**
  * Created by mbemis on 1/10/18.
  */
trait KeyCache {
  def onBoot(): Future[Unit]
  def getKey(userEmail: WorkbenchEmail, project: GoogleProject): Future[Option[String]]
  def getKey(user: WorkbenchUser, project: GoogleProject): Future[String]
  def removeKey(userId: WorkbenchUserId, project: GoogleProject, keyId: ServiceAccountKeyId): Future[Unit]
}

trait NoKeyCache extends KeyCache {
  override def onBoot(): Future[Unit] = Future.successful(())
  override def getKey(userEmail: WorkbenchEmail, project: GoogleProject): Future[Option[String]] = Future.successful(Option(""))
  override def getKey(user: WorkbenchUser, project: GoogleProject): Future[String] = Future.successful("")
  override def removeKey(userId: WorkbenchUserId, project: GoogleProject, keyId: ServiceAccountKeyId): Future[Unit] = Future.successful(())
}

object NoKeyCache extends NoKeyCache