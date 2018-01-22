package org.broadinstitute.dsde.workbench.sam.google

import java.io.ByteArrayInputStream

import akka.http.scaladsl.model.StatusCodes
import cats.data.OptionT
import cats.implicits._
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.storage.model.StorageObject
import org.broadinstitute.dsde.workbench.google.{GoogleIamDAO, GoogleStorageDAO}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKey, ServiceAccountKeyId}
import org.broadinstitute.dsde.workbench.sam.config.{GoogleServicesConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.service.KeyCache

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 1/10/18.
  */
class GoogleKeyCache(val googleIamDAO: GoogleIamDAO, val googleStorageDAO: GoogleStorageDAO, val googleServicesConfig: GoogleServicesConfig, val petServiceAccountConfig: PetServiceAccountConfig)(implicit val executionContext: ExecutionContext) extends KeyCache {

  override def onBoot(): Future[Unit] = {
    googleStorageDAO.createBucket(googleServicesConfig.serviceAccountClientProject, petServiceAccountConfig.keyBucketName).recover {
      case t: GoogleJsonResponseException if t.getDetails.getMessage.contains("You already own this bucket") && t.getDetails.getCode == 409 => ()
    } flatMap { _ => googleStorageDAO.setBucketLifecycle(petServiceAccountConfig.keyBucketName, petServiceAccountConfig.activeKeyMaxAge) }
  }

  override def getKey(pet: PetServiceAccount): Future[String] = {
    val retrievedKeys = for {
      keyObjects <- googleStorageDAO.listObjectsWithPrefix(petServiceAccountConfig.keyBucketName, keyNamePrefix(pet.id.project, pet.serviceAccount.email))
      keys <- googleIamDAO.listServiceAccountKeys(pet.id.project, pet.serviceAccount.email, true)
    } yield (keyObjects.toList, keys.toList)

    retrievedKeys.flatMap {
      case (Nil, _) => furnishNewKey(pet, pet.id.project) //mismatch. there were no keys found in the bucket, but there may be keys on the service account
      case (_, Nil) => furnishNewKey(pet, pet.id.project) //mismatch. there were no keys found on the service account, but there may be keys in the bucket
      case (keyObjects, keys) => retrieveActiveKey(pet, pet.id.project, keyObjects, keys)
    }
  }

  override def removeKey(pet: PetServiceAccount, keyId: ServiceAccountKeyId): Future[Unit] = {
    for {
      _ <- googleIamDAO.removeServiceAccountKey(pet.id.project, pet.serviceAccount.email, keyId)
      _ <- googleStorageDAO.removeObject(petServiceAccountConfig.keyBucketName, keyNameFull(pet.id.project, pet.serviceAccount.email, keyId))
    } yield ()
  }

  private def keyNamePrefix(project: GoogleProject, saEmail: WorkbenchEmail) = s"${project.value}/${saEmail.value}"
  private def keyNameFull(project: GoogleProject, saEmail: WorkbenchEmail, keyId: ServiceAccountKeyId) = s"${keyNamePrefix(project, saEmail)}/${keyId.value}"

  private def furnishNewKey(pet: PetServiceAccount, project: GoogleProject): Future[String] = {
    val keyFuture = for {
      key <- OptionT.liftF(googleIamDAO.createServiceAccountKey(project, pet.serviceAccount.email) recover {
        case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.TooManyRequests.intValue => throw new WorkbenchException("You have reached the 10 key limit on service accounts. Please remove one to create another.")
      })
      decodedKey <- OptionT.fromOption[Future](key.privateKeyData.decode)
      _ <- OptionT.liftF(googleStorageDAO.storeObject(petServiceAccountConfig.keyBucketName, keyNameFull(project, pet.serviceAccount.email, key.id), new ByteArrayInputStream(decodedKey.getBytes)))
    } yield decodedKey

    keyFuture.value.map(_.getOrElse(throw new WorkbenchException("Unable to furnish new key")))
  }

  private def retrieveActiveKey(pet: PetServiceAccount, project: GoogleProject, keyObjects: List[StorageObject], keys: List[ServiceAccountKey]): Future[String] = {
    val mostRecentKeyName = keyObjects.sortBy(_.getTimeCreated.getValue).last.getName //here is where we'll soon ask the question: "is this key still active?" if it's not, we'll create a new key

    googleStorageDAO.getObject(petServiceAccountConfig.keyBucketName, mostRecentKeyName).flatMap {
      case Some(key) => Future.successful(key.toString)
      case None => furnishNewKey(pet, project) //this is a case that should never occur, but if it does, we should furnish a new key
    }
  }

}