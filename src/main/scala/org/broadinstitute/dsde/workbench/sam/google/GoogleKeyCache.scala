package org.broadinstitute.dsde.workbench.sam.google

import java.io.ByteArrayInputStream

import akka.http.scaladsl.model.{DateTime, StatusCodes}
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.storage.model.StorageObject
import org.broadinstitute.dsde.workbench.google.{GoogleIamDAO, GoogleStorageDAO}
import org.broadinstitute.dsde.workbench.model.google.GoogleModelJsonSupport._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKey, ServiceAccountKeyId}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.config.{GoogleServicesConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.service.KeyCache

import scala.concurrent.{ExecutionContext, Future}
import spray.json._

/**
  * Created by mbemis on 1/10/18.
  */
class GoogleKeyCache(val directoryDAO: DirectoryDAO, val googleIamDAO: GoogleIamDAO, val googleStorageDAO: GoogleStorageDAO, val googleExtensions: GoogleExtensions, val googleServicesConfig: GoogleServicesConfig, val petServiceAccountConfig: PetServiceAccountConfig)(implicit val executionContext: ExecutionContext) extends KeyCache {

  override def onBoot(): Future[Unit] = {
    googleStorageDAO.createBucket(googleServicesConfig.serviceAccountClientProject, petServiceAccountConfig.keyBucketName).map { _ =>
      googleStorageDAO.setBucketLifecycle(petServiceAccountConfig.keyBucketName, petServiceAccountConfig.activeKeyMaxAge)
    }
  }

  private def keyNamePrefix(project: GoogleProject, saEmail: WorkbenchEmail) = s"${project.value}/${saEmail.value}"
  private def keyNameFull(project: GoogleProject, saEmail: WorkbenchEmail, keyId: ServiceAccountKeyId) = s"${keyNamePrefix(project, saEmail)}/${keyId.value}"

  def getKey(userEmail: WorkbenchEmail, project: GoogleProject): Future[Option[String]] = {
    for {
      subject <- directoryDAO.loadSubjectFromEmail(userEmail)
      result <- subject match {
        case Some(userId: WorkbenchUserId) => getKey(WorkbenchUser(userId, userEmail), project).map(Option(_))
        case _ => Future.successful(None)
      }
    } yield result
  }

  private def createFreshKey(user: WorkbenchUser, project: GoogleProject): Future[String] = {
    for {
      pet <- googleExtensions.createUserPetServiceAccount(user, project)
      key <- googleIamDAO.createServiceAccountKey(project, pet.serviceAccount.email) recover {
        case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.TooManyRequests.intValue => throw new WorkbenchException("You have reached the 10 key limit on service accounts. Please remove one to create another.")
      }
      _ <- googleStorageDAO.storeObject(petServiceAccountConfig.keyBucketName, keyNameFull(project, pet.serviceAccount.email, key.id), new ByteArrayInputStream(key.privateKeyData.decode.get.getBytes))
    } yield key.privateKeyData.decode.getOrElse(throw new WorkbenchException(s"could not decode service account private key data for key ${key.id}"))
  }

  private def retrieveActiveKey(user: WorkbenchUser, project: GoogleProject, keyObjects: List[StorageObject], keys: List[ServiceAccountKey]): Future[String] = {
    val mostRecentKeyName = keyObjects.sortBy(_.getTimeCreated.getValue).last.getName //here is where we'll soon ask the question: "is this key still active?" if it's not, we'll create a new key

    googleStorageDAO.getObject(petServiceAccountConfig.keyBucketName, mostRecentKeyName).flatMap {
      case Some(key) => Future.successful(key.toString)
      case None => createFreshKey(user, project) //this is a case that should never occur, but if it does, we should furnish a new key
    }
  }

  private def deleteKey(petSa: PetServiceAccount, keyId: ServiceAccountKeyId): Future[Unit] = {
   for {
     _ <- googleIamDAO.removeServiceAccountKey(petSa.id.project, petSa.serviceAccount.email, keyId)
     _ <- googleStorageDAO.removeObject(petServiceAccountConfig.keyBucketName, keyNameFull(petSa.id.project, petSa.serviceAccount.email, keyId))
   } yield ()
  }

  override def getKey(user: WorkbenchUser, project: GoogleProject): Future[String] = {
    val retrievedKeys = for {
      petSa <- googleExtensions.createUserPetServiceAccount(user, project)
      keyObjects <- googleStorageDAO.listObjectsWithPrefix(petServiceAccountConfig.keyBucketName, keyNamePrefix(project, petSa.serviceAccount.email))
      keys <- googleIamDAO.listServiceAccountKeys(project, petSa.serviceAccount.email)
    } yield (keyObjects.toList, keys.toList)

    retrievedKeys.flatMap {
      case (Nil, _) => createFreshKey(user, project) //mismatch. there were no keys found in the bucket, but there may be keys on the service account
      case (_, Nil) => createFreshKey(user, project) //mismatch. there were no keys found on the service account, but there may be keys in the bucket
      case (keyObjects, keys) => retrieveActiveKey(user, project, keyObjects, keys)
    }
  }

  override def removeKey(userId: WorkbenchUserId, project: GoogleProject, keyId: ServiceAccountKeyId): Future[Unit] = {
    for {
      maybePet <- directoryDAO.loadPetServiceAccount(PetServiceAccountId(userId, project))
      response <- maybePet match {
        case Some(pet) => deleteKey(pet, keyId)
        case None => Future.successful(())
      }
    } yield response
  }

}