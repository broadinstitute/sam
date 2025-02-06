package org.broadinstitute.dsde.workbench.sam.google

import java.nio.charset.Charset
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.implicits._
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.cloud.storage.{BucketInfo, StorageException}
import com.google.cloud.storage.BucketInfo.LifecycleRule
import com.google.pubsub.v1.ProjectTopicName
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.{GoogleIamDAO, GooglePubSubDAO, GoogleStorageDAO}
import org.broadinstitute.dsde.workbench.google2.{GcsBlobName, GoogleStorageService}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GcsObjectName, GoogleProject, ServiceAccountKey, ServiceAccountKeyId}
import org.broadinstitute.dsde.workbench.sam.config.{GoogleServicesConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.service.KeyCache
import fs2.Stream
import org.broadinstitute.dsde.workbench.sam.dataAccess.{LockDetails, PostgresDistributedLockDAO}
import org.broadinstitute.dsde.workbench.sam.model.CachedKey

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import org.broadinstitute.dsde.workbench.sam.model.CachedKey.keyPathPattern

import java.time.Instant

/** Created by mbemis on 1/10/18.
  */
class GoogleKeyCache(
    val distributedLock: PostgresDistributedLockDAO[IO],
    val googleIamDAO: GoogleIamDAO,
    val googleStorageDAO: GoogleStorageDAO, // this is only used for GoogleKeyCacheMonitorSupervisor to trigger pubsub notification.
    val googleStorageAlg: GoogleStorageService[IO],
    val googleKeyCachePubSubDao: GooglePubSubDAO,
    val googleServicesConfig: GoogleServicesConfig,
    val petServiceAccountConfig: PetServiceAccountConfig
)(implicit val executionContext: ExecutionContext)
    extends KeyCache
    with LazyLogging {
  val utf8Charset = Charset.forName("UTF-8")

  override def onBoot()(implicit system: ActorSystem): IO[Unit] = {
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
  }.compile.drain.flatTap(_ => startPubSubMonitor)

  private def startPubSubMonitor(implicit system: ActorSystem) = {
    val projectTopicName = ProjectTopicName
      .newBuilder()
      .setProject(googleServicesConfig.googleKeyCacheConfig.monitorPubSubConfig.project)
      .setTopic(googleServicesConfig.googleKeyCacheConfig.monitorPubSubConfig.topic)
      .build()
    for {
      ioRuntime <- GooglePubSubMonitor.createReceiverIORuntime(googleServicesConfig.googleKeyCacheConfig.monitorPubSubConfig)
      _ <- new GooglePubSubMonitor(
        googleKeyCachePubSubDao,
        googleServicesConfig.googleKeyCacheConfig.monitorPubSubConfig,
        googleServicesConfig.serviceAccountCredentialJson,
        new GoogleKeyCacheMessageReceiver(googleIamDAO)(ioRuntime)
      ) {
        override protected def init: IO[Unit] = for {
          _ <- super.init
          _ <- IO.fromFuture(
            IO(
              googleKeyCachePubSubDao.setTopicIamPermissions(
                projectTopicName.getTopic,
                Map(googleServicesConfig.projectServiceAccount -> "roles/pubsub.publisher")
              )
            )
          )
          _ <- IO.fromFuture(
            IO(
              googleStorageDAO.setObjectChangePubSubTrigger(
                googleServicesConfig.googleKeyCacheConfig.bucketName,
                projectTopicName.toString,
                List("OBJECT_DELETE")
              )
            )
          )
        } yield ()
      }.startAndRegisterTermination()
    } yield ()
  }

  override def getKey(pet: PetServiceAccount): IO[String] = {
    def maybeCreateKey(createKey: (List[GcsObjectName], List[ServiceAccountKey]) => IO[String]): IO[String] =
      for {
        (maybeActiveKey, keysFromCache, keysFromIam) <- retrieveActiveKey(pet)
        activeKey <- maybeActiveKey match {
          case Some(existingActiveKey) => IO.pure(existingActiveKey)
          case None => createKey(keysFromCache, keysFromIam)
        }
      } yield activeKey

    def cleanupAndCreateKey(keysFromCache: List[GcsObjectName], keysFromIam: List[ServiceAccountKey]): IO[String] = {
      logger.info(
        s"cleanupAndCreateKey: ${pet.id.project.value}-${pet.serviceAccount.subjectId.value} with ${keysFromCache.length} in cache and ${keysFromIam.length} in IAM"
      )
      for {
        _ <- IO.fromFuture(IO(cleanupUnknownKeys(pet, keysFromCache, keysFromIam)))
        key <- furnishNewKey(pet)
      } yield key
    }

    // 5 minute lock timeout chosen to match how long key creation polling can take
    val lockDetails = LockDetails(s"${pet.id.project.value}-getKey", pet.serviceAccount.subjectId.value, 5 minutes)
    maybeCreateKey((_, _) => distributedLock.withLock(lockDetails).use(_ => maybeCreateKey(cleanupAndCreateKey)))
  }

  private def fetchKeysFromCache(pet: PetServiceAccount): IO[List[CachedKey]] =
    googleStorageAlg
      .unsafeListObjectsWithPrefix(googleServicesConfig.googleKeyCacheConfig.bucketName, keyNamePrefix(pet.id.project, pet.serviceAccount.email))
      .map(gcsObjectList => gcsObjectList.map(CachedKey(_)))

  private def findActiveKey(pet: PetServiceAccount, withinLock: Boolean): IO[Option[String]] =
    for {
      keysInCache <- fetchKeysFromCache(pet)
      maybeActiveKey = searchCachedKeys(keysInCache, withinLock).map(_.value)
    } yield maybeActiveKey

  protected[google] def searchCachedKeys(keysInCache: List[CachedKey], withinLock: Boolean): Option[CachedKey] = {
    /* segment cached keys into ideal, retired, and nascent keys
        ideal: keys between 15 minutes and 12 days old. Use these whenever possible.
        retired: keys older than 12 days. Avoid if possible, but prefer these over nascent keys.
        nascent: keys newer than 15 minutes. Only use if nothing else is available; these may cause errors due to Google
          eventual consistency.
     */

    def oldestOf(keys: List[CachedKey]): Option[CachedKey] =
      Option(keys.minBy(_.timeCreated))

    def newestOf(keys: List[CachedKey]): Option[CachedKey] =
      Option(keys.maxBy(_.timeCreated))

    val now = Instant.now()

    val retirementTime = now.minusSeconds(googleServicesConfig.googleKeyCacheConfig.activeKeyMaxAge * (24L * 60 * 60))
    val nascentInstant = now.minusSeconds(googleServicesConfig.googleKeyCacheConfig.nascentKeyMinAgeMinutes * 60L)

    val (retiredKeys, unretiredKeys) = keysInCache.partition(_.isBefore(retirementTime))
    val (idealKeys, nascentKeys) = unretiredKeys.partition(_.isBefore(nascentInstant))

    if (idealKeys.nonEmpty) {
      // if any ideal keys exist, return the newest of those
      newestOf(idealKeys)
    } else if (nascentKeys.nonEmpty && retiredKeys.isEmpty) {
      // if any nascent keys exist but no retired keys exist, return the oldest nascent key
      oldestOf(nascentKeys)
    } else if (nascentKeys.nonEmpty && retiredKeys.nonEmpty) {
      // if both nascent and retired keys exist, return the newest retired key
      newestOf(retiredKeys)
    } else if (!withinLock) {
      // if not within lock, return None. This will signal callers to obtain a lock and re-call this function.
      None
    } else if (nascentKeys.isEmpty && retiredKeys.nonEmpty) {
      // if within lock, retired keys exist, and no nascent keys exist: return newest retired key and trigger key creation
      // TODO CORE-278: trigger new-key creation; can be async
      newestOf(retiredKeys)
    } else {
      // no keys exist; create one and return it
      // TODO CORE-278: trigger new-key creation and return its result
      None
    }

  }

  @deprecated
  private def fetchKeysFromCacheAndIam(pet: PetServiceAccount): IO[(List[GcsObjectName], List[ServiceAccountKey])] = {
    val fetchKeyFromCache = googleStorageAlg
      .unsafeListObjectsWithPrefix(googleServicesConfig.googleKeyCacheConfig.bucketName, keyNamePrefix(pet.id.project, pet.serviceAccount.email))
    val fetchKeyFromIam = IO.fromFuture(IO(googleIamDAO.listUserManagedServiceAccountKeys(pet.id.project, pet.serviceAccount.email).map(_.toList)))

    (fetchKeyFromCache, fetchKeyFromIam).parTupled
  }

  // TODO CORE-278: refactor this
  @deprecated
  private def retrieveActiveKey(pet: PetServiceAccount): IO[(Option[String], List[GcsObjectName], List[ServiceAccountKey])] =
    for {
      (keysFromCache, keysFromIam) <- fetchKeysFromCacheAndIam(pet)

      // TODO CORE-278: this could result in log spam
      _ = if (keysFromIam.length >= 10)
        logger.warn(s"danger: pet ${pet.serviceAccount.displayName.value} has ${keysFromIam.length} keys (cache has ${keysFromCache.length})")

      maybeActiveKey = keysFromCache.sortBy(_.timeCreated.toEpochMilli).findLast(isKeyActive(_, keysFromIam))
      activeKey <- maybeActiveKey
        .map { key =>
          googleStorageAlg
            .unsafeGetBlobBody(googleServicesConfig.googleKeyCacheConfig.bucketName, GcsBlobName(key.value))
        }
        .getOrElse(IO.pure(None))

    } yield (activeKey, keysFromCache, keysFromIam)

  override def removeKey(pet: PetServiceAccount, keyId: ServiceAccountKeyId): IO[Unit] =
    for {
      _ <- googleStorageAlg
        .removeObject(googleServicesConfig.googleKeyCacheConfig.bucketName, keyNameFull(pet.id.project, pet.serviceAccount.email, keyId))
        .compile
        .drain
      _ <- IO.fromFuture(IO(googleIamDAO.removeServiceAccountKey(pet.id.project, pet.serviceAccount.email, keyId)))
    } yield ()

  private[google] def keyNamePrefix(project: GoogleProject, saEmail: WorkbenchEmail) =
    s"${project.value}/${saEmail.value}/" // the google storage emulator doesn't return objects without `/` properly
  private def keyNameFull(project: GoogleProject, saEmail: WorkbenchEmail, keyId: ServiceAccountKeyId): GcsBlobName =
    GcsBlobName(s"${keyNamePrefix(project, saEmail)}${keyId.value}")

  private def furnishNewKey(pet: PetServiceAccount): IO[String] =
    for {
      key <- IO.fromFuture(IO(googleIamDAO.createServiceAccountKey(pet.id.project, pet.serviceAccount.email))) recover {
        // TODO CORE-278: on error, check the number of existing keys and purge as necessary
        case e: GoogleJsonResponseException =>
          if (e.getDetails.getCode == StatusCodes.TooManyRequests.intValue)
            // 2025-01-29: TooManyRequests is not returned by Google for this error any more. Leaving this in place
            //  in case Google switches back to it
            throw new WorkbenchException("You have reached the 10 key limit on service accounts. Please remove one to create another.")
          else if (e.getDetails.getCode == StatusCodes.BadRequest.intValue && e.getDetails.getMessage == "Precondition check failed.")
            throw new WorkbenchException("You may have reached the 10 key limit on service accounts.")
          else
            throw new WorkbenchException(s"Error creating key for service account: ${e.getDetails.getCode}: ${e.getDetails.getMessage}")
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

    // The key may exist in the Google bucket cache, but could have been deleted from the SA directly
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
