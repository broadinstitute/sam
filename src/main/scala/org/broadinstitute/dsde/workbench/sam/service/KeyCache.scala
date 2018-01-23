package org.broadinstitute.dsde.workbench.sam.service

import akka.actor.ActorSystem
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKeyId}
import org.broadinstitute.dsde.workbench.model.{PetServiceAccount, WorkbenchEmail, WorkbenchUser, WorkbenchUserId}

import scala.concurrent.Future

/**
  * Created by mbemis on 1/10/18.
  */
trait KeyCache {
  def onBoot()(implicit system: ActorSystem): Future[Unit]
  def getKey(pet: PetServiceAccount): Future[String]
  def removeKey(pet: PetServiceAccount, keyId: ServiceAccountKeyId): Future[Unit]
}

trait NoKeyCache extends KeyCache {
  override def onBoot()(implicit system: ActorSystem): Future[Unit] = Future.successful(())
  override def getKey(pet: PetServiceAccount): Future[String] = Future.successful("")
  override def removeKey(pet: PetServiceAccount, keyId: ServiceAccountKeyId): Future[Unit] = Future.successful(())
}

object NoKeyCache extends NoKeyCache