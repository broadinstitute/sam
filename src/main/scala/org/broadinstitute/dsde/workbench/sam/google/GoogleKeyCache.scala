package org.broadinstitute.dsde.workbench.sam.google

import java.nio.charset.Charset
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.implicits._
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.cloud.storage.{BucketInfo, StorageException}
import com.google.cloud.storage.BucketInfo.LifecycleRule
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.{GoogleIamDAO, GooglePubSubDAO, GoogleStorageDAO}
import org.broadinstitute.dsde.workbench.google2.{GcsBlobName, GoogleStorageService}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GcsObjectName, GoogleProject, ServiceAccountKey, ServiceAccountKeyId}
import org.broadinstitute.dsde.workbench.sam.config.{GoogleServicesConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.service.KeyCache
import fs2.Stream
import org.broadinstitute.dsde.workbench.sam.dataAccess.{LockDetails, PostgresDistributedLockDAO}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by mbemis on 1/10/18.
  */
class GoogleKeyCache(
                      val distributedLock: PostgresDistributedLockDAO[IO],
                      val googleIamDAO: GoogleIamDAO,
                      val googleStorageDAO: GoogleStorageDAO, //this is only used for GoogleKeyCacheMonitorSupervisor to trigger pubsub notification.
                      val googleStorageAlg: GoogleStorageService[IO],
                      val googleKeyCachePubSubDao: GooglePubSubDAO,
                      val googleServicesConfig: GoogleServicesConfig,
                      val petServiceAccountConfig: PetServiceAccountConfig)(implicit val executionContext: ExecutionContext)
    extends KeyCache
    with LazyLogging {
  val keyPathPattern = """([^\/]+)\/([^\/]+)\/([^\/]+)""".r
  val utf8Charset = Charset.forName("UTF-8")

  override def onBoot()(implicit system: ActorSystem): IO[Unit] = {
    system.actorOf(
      GoogleKeyCacheMonitorSupervisor.props(
        googleServicesConfig.googleKeyCacheConfig.monitorPubSubConfig.pollInterval,
        googleServicesConfig.googleKeyCacheConfig.monitorPubSubConfig.pollJitter,
        googleKeyCachePubSubDao,
        googleIamDAO,
        googleServicesConfig.googleKeyCacheConfig.monitorPubSubConfig.topic,
        googleServicesConfig.googleKeyCacheConfig.monitorPubSubConfig.subscription,
        googleServicesConfig.projectServiceAccount,
        googleServicesConfig.googleKeyCacheConfig.monitorPubSubConfig.workerCount,
        this
      ))

    googleStorageAlg
      .insertBucket(googleServicesConfig.serviceAccountClientProject, googleServicesConfig.googleKeyCacheConfig.bucketName)
      .recoverWith {
        case t: StorageException if t.getCode == 409 && t.getMessage.contains("You already own this bucket") =>
          fs2.Stream(logger.info(t.getMessage))
      } flatMap { _ =>
      val lifecycleCondition = BucketInfo.LifecycleRule.LifecycleCondition
        .newBuilder()
        .setAge(googleServicesConfig.googleKeyCacheConfig.retiredKeyMaxAge)
        .build()
      val lifecycleRule = new LifecycleRule(LifecycleRule.LifecycleAction.newDeleteAction(), lifecycleCondition)
      googleStorageAlg.setBucketLifecycle(googleServicesConfig.googleKeyCacheConfig.bucketName, List(lifecycleRule))
    }
  }.compile.drain

  override def getKey(pet: PetServiceAccount): IO[String] = {
    def maybeCreateKey(createKey: (List[GcsObjectName], List[ServiceAccountKey]) => IO[String]): IO[String] = {
      for {
        (maybeActiveKey, keysFromCache, keysFromIam) <- retrieveActiveKey(pet)
        activeKey <- maybeActiveKey match {
          case Some(existingActiveKey) => IO.pure(existingActiveKey)
          case None => createKey(keysFromCache, keysFromIam)
        }
      } yield activeKey
    }

    def cleanupAndCreateKey(keysFromCache: List[GcsObjectName], keysFromIam: List[ServiceAccountKey]): IO[String] = {
      for {
        _ <- IO.fromFuture(IO(cleanupUnknownKeys(pet, keysFromCache, keysFromIam)))
        key <- furnishNewKey(pet)
      } yield key
    }

    val lockDetails = LockDetails(s"${pet.id.project.value}-getKey", pet.serviceAccount.subjectId.value, 20 seconds)
    maybeCreateKey((_, _) =>
      distributedLock.withLock(lockDetails).use { _ => maybeCreateKey(cleanupAndCreateKey)
    })
  }

  private def fetchKeysFromCacheAndIam(pet: PetServiceAccount): IO[(List[GcsObjectName], List[ServiceAccountKey])] = {
    val fetchKeyFromCache = googleStorageAlg
      .unsafeListObjectsWithPrefix(googleServicesConfig.googleKeyCacheConfig.bucketName, keyNamePrefix(pet.id.project, pet.serviceAccount.email))
    val fetchKeyFromIam = IO.fromFuture(IO(googleIamDAO.listUserManagedServiceAccountKeys(pet.id.project, pet.serviceAccount.email).map(_.toList)))

    (fetchKeyFromCache, fetchKeyFromIam).parTupled
  }

  private def retrieveActiveKey(pet: PetServiceAccount): IO[(Option[String], List[GcsObjectName], List[ServiceAccountKey])] = {
    for {
      (keysFromCache, keysFromIam) <- fetchKeysFromCacheAndIam(pet)
      maybeMostRecentKey = keysFromCache.sortBy(_.timeCreated.toEpochMilli).lastOption
      mostRecentKey <- maybeMostRecentKey match {
        case Some(mostRecentKey) if isKeyActive(mostRecentKey, keysFromIam) =>
          googleStorageAlg
            .unsafeGetBlobBody(googleServicesConfig.googleKeyCacheConfig.bucketName, GcsBlobName(mostRecentKey.value))

        case _ => IO.pure(None)
      }
    } yield (mostRecentKey, keysFromCache, keysFromIam)
  }

  override def removeKey(pet: PetServiceAccount, keyId: ServiceAccountKeyId): IO[Unit] =
    for {
      _ <- googleStorageAlg.removeObject(googleServicesConfig.googleKeyCacheConfig.bucketName, keyNameFull(pet.id.project, pet.serviceAccount.email, keyId)).compile.drain
      _ <- IO.fromFuture(IO(googleIamDAO.removeServiceAccountKey(pet.id.project, pet.serviceAccount.email, keyId)))
    } yield ()

  private[google] def keyNamePrefix(project: GoogleProject, saEmail: WorkbenchEmail) =
    s"${project.value}/${saEmail.value}/" //the google storage emulator doesn't return objects without `/` properly
  private def keyNameFull(project: GoogleProject, saEmail: WorkbenchEmail, keyId: ServiceAccountKeyId): GcsBlobName =
    GcsBlobName(s"${keyNamePrefix(project, saEmail)}${keyId.value}")

  private def furnishNewKey(pet: PetServiceAccount): IO[String] =
    for {
      key <- IO.fromFuture(IO(googleIamDAO.createServiceAccountKey(pet.id.project, pet.serviceAccount.email))) recover {
        case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.TooManyRequests.intValue =>
          throw new WorkbenchException("You have reached the 10 key limit on service accounts. Please remove one to create another.")
      }
      decodedKey <- IO.fromEither(key.privateKeyData.decode.toRight(new WorkbenchException("Failed to decode retrieved key")))
      _ <- (Stream.emits(decodedKey.getBytes(utf8Charset)).covary[IO] through googleStorageAlg.streamUploadBlob(
        googleServicesConfig.googleKeyCacheConfig.bucketName,
        keyNameFull(pet.id.project, pet.serviceAccount.email, key.id)
      )).compile.drain
    } yield decodedKey

  private def isKeyActive(mostRecentKey: GcsObjectName, serviceAccountKeys: List[ServiceAccountKey]): Boolean = {
    val keyRetired = System.currentTimeMillis() - mostRecentKey.timeCreated.toEpochMilli > 86400000L * googleServicesConfig.googleKeyCacheConfig.activeKeyMaxAge

    val keyPathPattern(project, petSaEmail, keyId) = mostRecentKey.value

    //The key may exist in the Google bucket cache, but could have been deleted from the SA directly
    val keyExistsForSA = serviceAccountKeys.exists(_.id.value.contentEquals(keyId))

    !keyRetired && keyExistsForSA
  }

  private def cleanupUnknownKeys(pet: PetServiceAccount, cachedKeyObjects: List[GcsObjectName], serviceAccountKeys: List[ServiceAccountKey]): Future[Unit] = {
    val cachedKeyIds = cachedKeyObjects.map(_.value).collect { case keyPathPattern(_, _, keyId) => ServiceAccountKeyId(keyId) }
    val unknownKeyIds: Set[ServiceAccountKeyId] = serviceAccountKeys.map(_.id).toSet -- cachedKeyIds.toSet

    Future
      .traverse(unknownKeyIds) { keyId =>
        googleIamDAO.removeServiceAccountKey(pet.id.project, pet.serviceAccount.email, keyId)
      }
      .void
  }
}
