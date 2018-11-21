package org.broadinstitute.dsde.workbench.sam.google

import java.nio.charset.Charset

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import cats.data.OptionT
import cats.effect.IO
import cats.implicits._
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.cloud.storage.{BucketInfo, StorageException}
import com.google.cloud.storage.BucketInfo.LifecycleRule
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.util.{DistributedLock, LockPath}
import org.broadinstitute.dsde.workbench.google.{CollectionName, Document, GcsBlobName, GoogleIamDAO, GooglePubSubDAO, GoogleStorageDAO, GoogleStorageService}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GcsObjectName, GoogleProject, ServiceAccountKey, ServiceAccountKeyId}
import org.broadinstitute.dsde.workbench.sam.config.{GoogleServicesConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.service.KeyCache
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 1/10/18.
  */
class GoogleKeyCache(
    val distributedLock: DistributedLock[IO],
    val googleIamDAO: GoogleIamDAO,
    val googleStorageDAO: GoogleStorageDAO, //this is only used for GoogleKeyCacheMonitorSupervisor to trigger pubsub notification.
    val googleStorageAlg: GoogleStorageService[IO],
    val googlePubSubDAO: GooglePubSubDAO,
    val googleServicesConfig: GoogleServicesConfig,
    val petServiceAccountConfig: PetServiceAccountConfig)(implicit val executionContext: ExecutionContext)
    extends KeyCache
    with LazyLogging {
  val keyPathPattern = """([^\/]+)\/([^\/]+)\/([^\/]+)""".r
  val utf8Charset = Charset.forName("UTF-8")

  override def onBoot()(implicit system: ActorSystem): IO[Unit] = {
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

    googleStorageAlg
      .createBucket(googleServicesConfig.serviceAccountClientProject, googleServicesConfig.googleKeyCacheConfig.bucketName, List.empty)
      .recoverWith {
        case t: StorageException if t.getCode == 409 && t.getMessage.contains("You already own this bucket") =>
          IO(logger.info(t.getMessage))
      } flatMap { _ =>
      val lifecycleCondition = BucketInfo.LifecycleRule.LifecycleCondition
        .newBuilder()
        .setAge(googleServicesConfig.googleKeyCacheConfig.retiredKeyMaxAge)
        .build()
      val lifecycleRule = new LifecycleRule(LifecycleRule.LifecycleAction.newDeleteAction(), lifecycleCondition)
      googleStorageAlg.setBucketLifecycle(googleServicesConfig.googleKeyCacheConfig.bucketName, List(lifecycleRule))
    }
  }

  override def getKey(pet: PetServiceAccount): IO[String] = {
    val getKeyIO = for {
      keyObjects <- googleStorageAlg
        .unsafeListObjectsWithPrefix(googleServicesConfig.googleKeyCacheConfig.bucketName, keyNamePrefix(pet.id.project, pet.serviceAccount.email))
      keys <- IO.fromFuture(IO(googleIamDAO.listUserManagedServiceAccountKeys(pet.id.project, pet.serviceAccount.email).map(_.toList)))
      cleanedKeyObjects <- IO.fromFuture(IO(cleanupUnknownKeys(pet, keyObjects, keys)))
      res <- (cleanedKeyObjects, keys) match {
        case (Nil, _) =>
          furnishNewKey(pet) //mismatch. there were no keys found in the bucket, but there may be keys on the service account
        case (_, Nil) =>
          furnishNewKey(pet) //mismatch. there were no keys found on the service account, but there may be keys in the bucket
        case (keyObjects, serviceAccountKeys) =>
          retrieveActiveKey(pet, keyObjects, serviceAccountKeys)
      }
    } yield res
    val lockPath = LockPath(CollectionName(s"${pet.id.project.value}-getKey"), Document(pet.serviceAccount.subjectId.value), 20 seconds)
    distributedLock.withLock(lockPath).use(_ => getKeyIO)
  }

  override def removeKey(pet: PetServiceAccount, keyId: ServiceAccountKeyId): IO[Unit] =
    for {
      _ <- googleStorageAlg.removeObject(googleServicesConfig.googleKeyCacheConfig.bucketName, keyNameFull(pet.id.project, pet.serviceAccount.email, keyId))
      _ <- IO.fromFuture(IO(googleIamDAO.removeServiceAccountKey(pet.id.project, pet.serviceAccount.email, keyId)))
    } yield ()

  private[google] def keyNamePrefix(project: GoogleProject, saEmail: WorkbenchEmail) =
    s"${project.value}/${saEmail.value}/" //the google storage emulator doesn't return objects without `/` properly
  private def keyNameFull(project: GoogleProject, saEmail: WorkbenchEmail, keyId: ServiceAccountKeyId): GcsBlobName =
    GcsBlobName(s"${keyNamePrefix(project, saEmail)}${keyId.value}")

  private def furnishNewKey(pet: PetServiceAccount): IO[String] = {
    val keyFuture = for {
      key <- OptionT.liftF(IO.fromFuture(IO(googleIamDAO.createServiceAccountKey(pet.id.project, pet.serviceAccount.email))) recover {
        case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.TooManyRequests.intValue =>
          throw new WorkbenchException("You have reached the 10 key limit on service accounts. Please remove one to create another.")
      })
      decodedKey <- OptionT.fromOption[IO](key.privateKeyData.decode)
      _ <- OptionT.liftF(
        googleStorageAlg.storeObject(
          googleServicesConfig.googleKeyCacheConfig.bucketName,
          keyNameFull(pet.id.project, pet.serviceAccount.email, key.id),
          decodedKey.getBytes(utf8Charset),
          "text/plain"
        ))

    } yield decodedKey

    keyFuture.value.map(_.getOrElse(throw new WorkbenchException("Unable to furnish new key")))
  }

  private def retrieveActiveKey(pet: PetServiceAccount, cachedKeyObjects: List[GcsObjectName], serviceAccountKeys: List[ServiceAccountKey]): IO[String] = {
    val mostRecentKey = cachedKeyObjects.sortBy(_.timeCreated.toEpochMilli).last
    val keyRetired = System.currentTimeMillis() - mostRecentKey.timeCreated.toEpochMilli > 86400000L * googleServicesConfig.googleKeyCacheConfig.activeKeyMaxAge

    val keyPathPattern(project, petSaEmail, keyId) = mostRecentKey.value

    //The key may exist in the Google bucket cache, but could have been deleted from the SA directly
    val keyExistsForSA = serviceAccountKeys.exists(_.id.value.contentEquals(keyId))

    if (keyRetired || !keyExistsForSA) {
      furnishNewKey(pet)
    } else {
      googleStorageAlg.unsafeGetObject(googleServicesConfig.googleKeyCacheConfig.bucketName, GcsBlobName(mostRecentKey.value)).flatMap {
        case Some(key) => IO.pure(key.toString)
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
