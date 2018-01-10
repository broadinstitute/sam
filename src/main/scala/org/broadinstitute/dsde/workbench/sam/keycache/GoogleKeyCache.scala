package org.broadinstitute.dsde.workbench.sam.keycache

import java.io.ByteArrayInputStream

import akka.http.scaladsl.model.StatusCodes
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import org.broadinstitute.dsde.workbench.google.{GoogleIamDAO, GoogleStorageDAO}
import org.broadinstitute.dsde.workbench.model.{PetServiceAccountId, WorkbenchException, WorkbenchUser, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.model.google.GoogleModelJsonSupport._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKey, ServiceAccountKeyId}
import org.broadinstitute.dsde.workbench.sam.config.{GoogleServicesConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.google.GoogleExtensions

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 1/10/18.
  */
class GoogleKeyCache(val directoryDAO: DirectoryDAO, val googleIamDAO: GoogleIamDAO, val googleStorageDAO: GoogleStorageDAO, val googleExtensions: GoogleExtensions, val googleServicesConfig: GoogleServicesConfig, val petServiceAccountConfig: PetServiceAccountConfig)(implicit val executionContext: ExecutionContext) extends KeyCache {

  override def onBoot(): Future[Unit] = {
    googleStorageDAO.createBucket(googleServicesConfig.serviceAccountClientProject, petServiceAccountConfig.keyBucketName).map { _ => () }
  }

  override def getKey(user: WorkbenchUser, project: GoogleProject): Future[ServiceAccountKey] = {
    import spray.json._

    googleStorageDAO.getObject(petServiceAccountConfig.keyBucketName, s"${user.id.value}-${project.value}.json").flatMap {
      case Some(key) => Future.successful(key.toString.parseJson.convertTo[ServiceAccountKey])
      case None =>
        for {
          pet <- googleExtensions.createUserPetServiceAccount(user, project)
          key <- googleIamDAO.createServiceAccountKey(project, pet.serviceAccount.email) recover {
            case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.TooManyRequests.intValue => throw new WorkbenchException("You have reached the 10 key limit on service accounts. Please remove one to create another.")
          }
          _ <- googleStorageDAO.storeObject(petServiceAccountConfig.keyBucketName, s"${user.id.value}-${project.value}.json", new ByteArrayInputStream(key.toJson.prettyPrint.getBytes), "text/plain") //damnit i broke making this last param optional
        } yield key
    }
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
