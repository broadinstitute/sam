package org.broadinstitute.dsde.workbench.sam.service

import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKeyId}
import org.broadinstitute.dsde.workbench.model.{PetServiceAccount, WorkbenchEmail, WorkbenchUser, WorkbenchUserId}

import scala.concurrent.Future

/**
  * Created by mbemis on 1/10/18.
  */
trait KeyCache {
  def onBoot(): Future[Unit]
  def getKey(pet: PetServiceAccount, project: GoogleProject): Future[String]
  def removeKey(pet: PetServiceAccount, project: GoogleProject, keyId: ServiceAccountKeyId): Future[Unit]
}

trait NoKeyCache extends KeyCache {
  override def onBoot(): Future[Unit] = Future.successful(())
  override def getKey(pet: PetServiceAccount, project: GoogleProject): Future[String] = Future.successful("")
  override def removeKey(pet: PetServiceAccount, project: GoogleProject, keyId: ServiceAccountKeyId): Future[Unit] = Future.successful(())
}

object NoKeyCache extends NoKeyCache