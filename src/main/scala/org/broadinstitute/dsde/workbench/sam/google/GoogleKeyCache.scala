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

/**
  * Created by mbemis on 1/10/18.
  */
class GoogleKeyCache(val directoryDAO: DirectoryDAO, val googleIamDAO: GoogleIamDAO, val googleStorageDAO: GoogleStorageDAO, val googleExtensions: GoogleExtensions, val googleServicesConfig: GoogleServicesConfig, val petServiceAccountConfig: PetServiceAccountConfig)(implicit val executionContext: ExecutionContext) extends KeyCache {

  private val PET_EXPIRATION_DAYS: Int = 2 //temp

  override def onBoot(): Future[Unit] = {
    googleStorageDAO.createBucket(googleServicesConfig.serviceAccountClientProject, petServiceAccountConfig.keyBucketName).map { _ =>
      googleStorageDAO.setBucketLifecycle(petServiceAccountConfig.keyBucketName, PET_EXPIRATION_DAYS) //todo: read age from config
    }
  }

  private def keyNameFormat(project: GoogleProject, saEmail: WorkbenchEmail, keyId: ServiceAccountKeyId) = s"${project.value}/${saEmail.value}/${keyId.value}"
  private def keyNamePrefix(project: GoogleProject, saEmail: WorkbenchEmail) = s"${project.value}/${saEmail.value}"


  override def getKey(user: WorkbenchUser, project: GoogleProject): Future[ServiceAccountKey] = {
    import spray.json._


    val retrievedKeys = for {
      petSa <- googleExtensions.createUserPetServiceAccount(user, project)
      keyObjects <- googleStorageDAO.listObjectsWithPrefix(petServiceAccountConfig.keyBucketName, keyNamePrefix(project, petSa.serviceAccount.email))
      keys <- googleIamDAO.listServiceAccountKeys(project, petSa.serviceAccount.email)
    } yield (keyObjects, keys)

    retrievedKeys.flatMap { case (keyObjects, keys) =>
      if(keyObjects.isEmpty && keys.isEmpty) { //todo: use pattern matching
        for {
          pet <- googleExtensions.createUserPetServiceAccount(user, project)
          key <- googleIamDAO.createServiceAccountKey(project, pet.serviceAccount.email) recover {
            case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.TooManyRequests.intValue => throw new WorkbenchException("You have reached the 10 key limit on service accounts. Please remove one to create another.")
          }
          _ <- googleStorageDAO.storeObject(petServiceAccountConfig.keyBucketName, keyNameFormat(project, pet.serviceAccount.email, key.id), new ByteArrayInputStream(key.toJson.prettyPrint.getBytes)) //damnit i broke making this last param optional
        } yield key
      }
      else if(keyObjects.isEmpty && keys.nonEmpty) {
        //??? maybe we need to wait for the key to show up in the bucket? reminder: there are "phantom" keys
        throw new WorkbenchException("The key isn't in the bucket yet. Are we looking for a phantom key?")
      }
      else if(keyObjects.nonEmpty && keys.isEmpty) {
        //ok this is a weird case but we should deal with it
        throw new WorkbenchException("We saw the key, but we can't get it!")
      }
      else {
        //get the active key, which is the most recently created key *within the activity window* (make sure it's not retired)
        val theKeyName = keyObjects.sortBy(_.getTimeCreated.getValue).last.getName

        googleStorageDAO.getObject(petServiceAccountConfig.keyBucketName, theKeyName).flatMap {
          case Some(key) => Future.successful(key.toString.parseJson.convertTo[ServiceAccountKey])
          case None => throw new WorkbenchException("We saw the key, but we can't get it!")
        }
      }
    }







//    googleStorageDAO.getObject(petServiceAccountConfig.keyBucketName, keyNameFormat(project, )).flatMap {
//      case Some(key) => Future.successful(key.toString.parseJson.convertTo[ServiceAccountKey])
//      case None =>
//        for {
//          pet <- googleExtensions.createUserPetServiceAccount(user, project)
//          key <- googleIamDAO.createServiceAccountKey(project, pet.serviceAccount.email) recover {
//            case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.TooManyRequests.intValue => throw new WorkbenchException("You have reached the 10 key limit on service accounts. Please remove one to create another.")
//          }
//          _ <- googleStorageDAO.storeObject(petServiceAccountConfig.keyBucketName, s"${user.id.value}-${project.value}.json", new ByteArrayInputStream(key.toJson.prettyPrint.getBytes), "text/plain") //damnit i broke making this last param optional
//        } yield key
//    }
  }

  override def removeKey(userId: WorkbenchUserId, project: GoogleProject, keyId: ServiceAccountKeyId): Future[Unit] = {
    for {
      maybePet <- directoryDAO.loadPetServiceAccount(PetServiceAccountId(userId, project))
      response <- maybePet match {
        case Some(pet) => googleIamDAO.removeServiceAccountKey(project, pet.serviceAccount.email, keyId)
        case None => Future.successful(())
      }
    } yield response
  }

}
