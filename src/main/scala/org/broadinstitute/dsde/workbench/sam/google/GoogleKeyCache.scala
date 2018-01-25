package org.broadinstitute.dsde.workbench.sam.google

import java.io.ByteArrayInputStream

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import cats.data.OptionT
import cats.implicits._
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.storage.model.StorageObject
import org.broadinstitute.dsde.workbench.google.{GoogleIamDAO, GooglePubSubDAO, GoogleStorageDAO}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKeyId}
import org.broadinstitute.dsde.workbench.sam.config.{GoogleServicesConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.service.KeyCache

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 1/10/18.
  */
class GoogleKeyCache(val googleIamDAO: GoogleIamDAO, val googleStorageDAO: GoogleStorageDAO, val googlePubSubDAO: GooglePubSubDAO, val googleServicesConfig: GoogleServicesConfig, val petServiceAccountConfig: PetServiceAccountConfig)(implicit val executionContext: ExecutionContext) extends KeyCache {

  override def onBoot()(implicit system: ActorSystem): Future[Unit] = {
    system.actorOf(GoogleKeyCacheMonitorSupervisor.props(
      googleServicesConfig.googleKeyCacheConfig.monitorPollInterval,
      googleServicesConfig.googleKeyCacheConfig.monitorPollJitter,
      googlePubSubDAO,
      googleIamDAO,
      googleServicesConfig.googleKeyCacheConfig.monitorTopic,
      googleServicesConfig.googleKeyCacheConfig.monitorSubscription,
      googleServicesConfig.projectServiceAccount,
      googleServicesConfig.googleKeyCacheConfig.monitorWorkerCount,
      this
    ))

    googleStorageDAO.createBucket(googleServicesConfig.serviceAccountClientProject, googleServicesConfig.googleKeyCacheConfig.bucketName).recover {
      case t: GoogleJsonResponseException if t.getDetails.getMessage.contains("You already own this bucket") && t.getDetails.getCode == 409 => ()
    } flatMap { _ => googleStorageDAO.setBucketLifecycle(googleServicesConfig.googleKeyCacheConfig.bucketName, googleServicesConfig.googleKeyCacheConfig.activeKeyMaxAge) }
  }

  override def getKey(pet: PetServiceAccount): Future[String] = {
    val retrievedKeys = for {
      keyObjects <- googleStorageDAO.listObjectsWithPrefix(googleServicesConfig.googleKeyCacheConfig.bucketName, keyNamePrefix(pet.id.project, pet.serviceAccount.email))
      keys <- googleIamDAO.listUserManagedServiceAccountKeys(pet.id.project, pet.serviceAccount.email)
    } yield (keyObjects.toList, keys.toList)

    retrievedKeys.flatMap {
      case (Nil, _) => furnishNewKey(pet) //mismatch. there were no keys found in the bucket, but there may be keys on the service account
      case (_, Nil) => furnishNewKey(pet) //mismatch. there were no keys found on the service account, but there may be keys in the bucket
      case (keyObjects, _) => retrieveActiveKey(pet, keyObjects)
    }
  }

  override def removeKey(pet: PetServiceAccount, keyId: ServiceAccountKeyId): Future[Unit] = {
    for {
      _ <- googleStorageDAO.removeObject(googleServicesConfig.googleKeyCacheConfig.bucketName, keyNameFull(pet.id.project, pet.serviceAccount.email, keyId))
      _ <- googleIamDAO.removeServiceAccountKey(pet.id.project, pet.serviceAccount.email, keyId)
    } yield ()
  }

  private def keyNamePrefix(project: GoogleProject, saEmail: WorkbenchEmail) = s"${project.value}/${saEmail.value}"
  private def keyNameFull(project: GoogleProject, saEmail: WorkbenchEmail, keyId: ServiceAccountKeyId) = s"${keyNamePrefix(project, saEmail)}/${keyId.value}"

  private def furnishNewKey(pet: PetServiceAccount): Future[String] = {
    val keyFuture = for {
      key <- OptionT.liftF(googleIamDAO.createServiceAccountKey(pet.id.project, pet.serviceAccount.email) recover {
        case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.TooManyRequests.intValue => throw new WorkbenchException("You have reached the 10 key limit on service accounts. Please remove one to create another.")
      })
      decodedKey <- OptionT.fromOption[Future](key.privateKeyData.decode)
      _ <- OptionT.liftF(googleStorageDAO.storeObject(googleServicesConfig.googleKeyCacheConfig.bucketName, keyNameFull(pet.id.project, pet.serviceAccount.email, key.id), new ByteArrayInputStream(decodedKey.getBytes)))
    } yield decodedKey

    keyFuture.value.map(_.getOrElse(throw new WorkbenchException("Unable to furnish new key")))
  }

  private def retrieveActiveKey(pet: PetServiceAccount, keyObjects: List[StorageObject]): Future[String] = {
    val mostRecentKey = keyObjects.sortBy(_.getTimeCreated.getValue).last
    val keyRetired = System.currentTimeMillis() - mostRecentKey.getTimeCreated.getValue > 30000 //harcdoded to 30 seconds for development

    if(keyRetired) furnishNewKey(pet)
    else {
      googleStorageDAO.getObject(googleServicesConfig.googleKeyCacheConfig.bucketName, mostRecentKey.getName).flatMap {
        case Some(key) => Future.successful(key.toString)
        case None => furnishNewKey(pet) //this is a case that should never occur, but if it does, we should furnish a new key
      }
    }
  }

}