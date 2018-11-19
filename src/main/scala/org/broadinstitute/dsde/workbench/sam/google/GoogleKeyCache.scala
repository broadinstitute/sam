package org.broadinstitute.dsde.workbench.sam.google

import java.io.ByteArrayInputStream

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import cats.data.OptionT
import cats.implicits._
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import org.broadinstitute.dsde.workbench.google.{GoogleIamDAO, GooglePubSubDAO, GoogleStorageDAO}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GcsObjectName, GoogleProject, ServiceAccountKey, ServiceAccountKeyId}
import org.broadinstitute.dsde.workbench.sam.config.{GoogleServicesConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.service.KeyCache

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 1/10/18.
  */
class GoogleKeyCache(
    val googleIamDAO: GoogleIamDAO,
    val googleStorageDAO: GoogleStorageDAO,
    val googlePubSubDAO: GooglePubSubDAO,
    val googleServicesConfig: GoogleServicesConfig,
    val petServiceAccountConfig: PetServiceAccountConfig)(implicit val executionContext: ExecutionContext)
    extends KeyCache {
  val keyPathPattern = """([^\/]+)\/([^\/]+)\/([^\/]+)""".r

  override def onBoot()(implicit system: ActorSystem): Future[Unit] = {
    system.actorOf(
      GoogleKeyCacheMonitorSupervisor.props(
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
    } flatMap { _ =>
      googleStorageDAO.setBucketLifecycle(googleServicesConfig.googleKeyCacheConfig.bucketName, googleServicesConfig.googleKeyCacheConfig.retiredKeyMaxAge)
    }
  }

  override def getKey(pet: PetServiceAccount): Future[String] = {
    val retrievedKeys = for {
      keyObjects <- googleStorageDAO.listObjectsWithPrefix(
        googleServicesConfig.googleKeyCacheConfig.bucketName,
        keyNamePrefix(pet.id.project, pet.serviceAccount.email))
      keys <- googleIamDAO.listUserManagedServiceAccountKeys(pet.id.project, pet.serviceAccount.email).map(_.toList)
      cleanedKeyObjects <- cleanupUnknownKeys(pet, keyObjects, keys)
    } yield (cleanedKeyObjects, keys)

    retrievedKeys.flatMap {
      case (Nil, _) => furnishNewKey(pet) //mismatch. there were no keys found in the bucket, but there may be keys on the service account
      case (_, Nil) => furnishNewKey(pet) //mismatch. there were no keys found on the service account, but there may be keys in the bucket
      case (keyObjects, serviceAccountKeys) => retrieveActiveKey(pet, keyObjects, serviceAccountKeys)
    }
  }

  override def removeKey(pet: PetServiceAccount, keyId: ServiceAccountKeyId): Future[Unit] =
    for {
      _ <- googleStorageDAO.removeObject(googleServicesConfig.googleKeyCacheConfig.bucketName, keyNameFull(pet.id.project, pet.serviceAccount.email, keyId))
      _ <- googleIamDAO.removeServiceAccountKey(pet.id.project, pet.serviceAccount.email, keyId)
    } yield ()

  private[google] def keyNamePrefix(project: GoogleProject, saEmail: WorkbenchEmail) = s"${project.value}/${saEmail.value}"
  private def keyNameFull(project: GoogleProject, saEmail: WorkbenchEmail, keyId: ServiceAccountKeyId) =
    GcsObjectName(s"${keyNamePrefix(project, saEmail)}/${keyId.value}")

  private def furnishNewKey(pet: PetServiceAccount): Future[String] = {
    val keyFuture = for {
      key <- OptionT.liftF(googleIamDAO.createServiceAccountKey(pet.id.project, pet.serviceAccount.email) recover {
        case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.TooManyRequests.intValue =>
          throw new WorkbenchException("You have reached the 10 key limit on service accounts. Please remove one to create another.")
      })
      decodedKey <- OptionT.fromOption[Future](key.privateKeyData.decode)
      _ <- OptionT.liftF(
        googleStorageDAO.storeObject(
          googleServicesConfig.googleKeyCacheConfig.bucketName,
          keyNameFull(pet.id.project, pet.serviceAccount.email, key.id),
          new ByteArrayInputStream(decodedKey.getBytes),
          "text/plain"
        ))
    } yield decodedKey

    keyFuture.value.map(_.getOrElse(throw new WorkbenchException("Unable to furnish new key")))
  }

  private def retrieveActiveKey(pet: PetServiceAccount, cachedKeyObjects: List[GcsObjectName], serviceAccountKeys: List[ServiceAccountKey]): Future[String] = {
    val mostRecentKey = cachedKeyObjects.sortBy(_.timeCreated.toEpochMilli).last
    val keyRetired = System.currentTimeMillis() - mostRecentKey.timeCreated.toEpochMilli > 86400000L * googleServicesConfig.googleKeyCacheConfig.activeKeyMaxAge

    val keyPathPattern(project, petSaEmail, keyId) = mostRecentKey.value

    //The key may exist in the Google bucket cache, but could have been deleted from the SA directly
    val keyExistsForSA = serviceAccountKeys.exists(_.id.value.contentEquals(keyId))

    if (keyRetired || !keyExistsForSA) {
      furnishNewKey(pet)
    } else {
      googleStorageDAO.getObject(googleServicesConfig.googleKeyCacheConfig.bucketName, mostRecentKey).flatMap {
        case Some(key) => Future.successful(key.toString)
        case None => furnishNewKey(pet) //this is a case that should never occur, but if it does, we should furnish a new key
      }
    }
  }

  private def cleanupUnknownKeys(
      pet: PetServiceAccount,
      cachedKeyObjects: List[GcsObjectName],
      serviceAccountKeys: List[ServiceAccountKey]): Future[List[GcsObjectName]] = {
    val cachedKeyIds = cachedKeyObjects.map(_.value).collect { case keyPathPattern(_, _, keyId) => ServiceAccountKeyId(keyId) }
    val unknownKeyIds: Set[ServiceAccountKeyId] = serviceAccountKeys.map(_.id).toSet -- cachedKeyIds.toSet
    val knownKeyCachedObjects = cachedKeyObjects.filter { cachedObject =>
      serviceAccountKeys.exists(key => cachedObject.value.endsWith(key.id.value))
    }

    Future
      .traverse(unknownKeyIds) { keyId =>
        googleIamDAO.removeServiceAccountKey(pet.id.project, pet.serviceAccount.email, keyId)
      }
      .map(_ => knownKeyCachedObjects)
  }
}
