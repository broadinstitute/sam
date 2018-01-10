package org.broadinstitute.dsde.workbench.sam.keycache

import java.io.ByteArrayInputStream

import akka.http.scaladsl.model.StatusCodes
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import org.broadinstitute.dsde.workbench.model.{WorkbenchException, WorkbenchUser}
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKey}

import scala.concurrent.Future

/**
  * Created by mbemis on 1/10/18.
  */
class GoogleKeyCache extends KeyCache {

//  def getPetServiceAccountKey(user: WorkbenchUser, project: GoogleProject): Future[ServiceAccountKey] = {
//    import spray.json._
//
//    googleStorageDAO.getObject(petServiceAccountConfig.keyBucketName, s"${user.id.value}-${project.value}.json").flatMap {
//      case Some(key) => Future.successful(key.toString.parseJson.convertTo[ServiceAccountKey])
//      case None =>
//        for {
//          pet <- createUserPetServiceAccount(user, project)
//          key <- googleIamDAO.createServiceAccountKey(project, pet.serviceAccount.email) recover {
//            case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.TooManyRequests.intValue => throw new WorkbenchException("You have reached the 10 key limit on service accounts. Please remove one to create another.")
//          }
//          _ <- googleStorageDAO.storeObject(petServiceAccountConfig.keyBucketName, s"${user.id.value}-${project.value}.json", new ByteArrayInputStream(key.toJson.prettyPrint.getBytes))
//        } yield key
//    }
//  }

}
