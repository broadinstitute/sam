package org.broadinstitute.dsde.workbench.sam.service

import akka.actor.ActorSystem
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.PetServiceAccount
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountKeyId

/**
  * Created by mbemis on 1/10/18.
  */
trait KeyCache {
  def onBoot()(implicit system: ActorSystem): IO[Unit]
  def getKey(pet: PetServiceAccount): IO[String]
  def removeKey(pet: PetServiceAccount, keyId: ServiceAccountKeyId): IO[Unit]
}
