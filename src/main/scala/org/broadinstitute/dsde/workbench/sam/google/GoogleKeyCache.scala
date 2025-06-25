package org.broadinstitute.dsde.workbench.sam.google

import java.nio.charset.Charset
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.implicits._
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.cloud.storage.{BucketInfo, NotificationInfo, StorageException}
import com.google.cloud.storage.BucketInfo.LifecycleRule
import com.google.cloud.storage.NotificationInfo.{EventType, PayloadFormat}
import com.google.pubsub.v1.ProjectTopicName
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.{GoogleIamDAO, GooglePubSubDAO}
import org.broadinstitute.dsde.workbench.google2.{GcsBlobName, GoogleStorageService}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKey, ServiceAccountKeyId}
import org.broadinstitute.dsde.workbench.sam.config.{GoogleServicesConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.service.KeyCache
import fs2.Stream
import org.broadinstitute.dsde.workbench.sam.dataAccess.{LockDetails, PostgresDistributedLockDAO}
import org.broadinstitute.dsde.workbench.sam.model.CachedKey

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import java.time.Instant

/** Created by mbemis on 1/10/18.
  */
class GoogleKeyCache(
    val distributedLock: PostgresDistributedLockDAO[IO],
    val googleIamDAO: GoogleIamDAO,
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
          _ <- googleStorageAlg
            .createNotificationIfNotExists(
              googleServicesConfig.googleKeyCacheConfig.bucketName,
              NotificationInfo
                .newBuilder(projectTopicName.toString)
                .setEventTypes(EventType.OBJECT_DELETE)
                .setPayloadFormat(PayloadFormat.JSON_API_V1)
                .build()
            )
            .compile
            .drain
        } yield ()
      }.startAndRegisterTermination()
    } yield ()
  }

  /** Retrieve a key for this pet, creating keys as necessary.
    */
  override def getKey(pet: PetServiceAccount): IO[String] = {
    val lockDetails = LockDetails(s"${pet.id.project.value}-getKey", pet.serviceAccount.subjectId.value, 20 seconds)

    for {
      // try to find a key using read-only logic by specifying withinLock = false
      maybeActiveKey <- findActiveKey(pet, withinLock = false)
      activeKey <- maybeActiveKey match {
        case Some(key) => IO.pure(key)
        case None =>
          // obtain a lock, then try again to find a key, allowing key creation by specifying withinLock = true
          distributedLock
            .withLock(lockDetails)
            .use(_ => findActiveKey(pet, withinLock = true).attempt)
            .map {
              case Right(Some(key)) => key
              case Right(None) => throw new WorkbenchException("Could not create or retrieve key: unexpected problem")
              case Left(t) =>
                throw new WorkbenchException(s"Could not create or retrieve key: ${t.getMessage}")
            }
      }
    } yield activeKey
  }

  /** List all cached keys from the Google bucket for this pet.
    */
  private def fetchKeysFromCache(pet: PetServiceAccount): IO[List[CachedKey]] =
    googleStorageAlg
      .unsafeListObjectsWithPrefix(googleServicesConfig.googleKeyCacheConfig.bucketName, keyNamePrefix(pet.id.project, pet.serviceAccount.email))
      .map(gcsObjectList => gcsObjectList.map(CachedKey(_)))

  /** List all keys in IAM for this pet.
    */
  private def fetchKeysFromIam(pet: PetServiceAccount): IO[List[ServiceAccountKey]] =
    IO.fromFuture(IO(googleIamDAO.listUserManagedServiceAccountKeys(pet.id.project, pet.serviceAccount.email).map(_.toList)))

  /** List all cached keys from the Google bucket, then try to find a usable key from that list.
    */
  private def findActiveKey(pet: PetServiceAccount, withinLock: Boolean): IO[Option[String]] =
    for {
      keysInCache <- fetchKeysFromCache(pet)
      maybeActiveKey <- searchCachedKeys(pet, keysInCache, withinLock)
    } yield maybeActiveKey

  /** Given a list of cached keys, return the most appropriate cached key. The priority order of keys is:
    *   1. keys older than 15 minutes but younger than 12 days 2. keys older than 12 days, which may be nearing the end of their life 3. keys younger than 15
    *      minutes, which may not yet be functional due to Google eventual-consistency
    *
    * When executing outside a lock, based on the withinLock parameter, this function operates in read-only mode and will return None in any cases where we
    * should be creating a new key.
    *
    * When executing inside a lock, based on the withinLock parameter, this function operates in read/write mode. It will create new keys when necessary.
    */
  protected[google] def searchCachedKeys(pet: PetServiceAccount, keysInCache: List[CachedKey], withinLock: Boolean): IO[Option[String]] = {
    // helpers
    def readFromCache(key: CachedKey): IO[Option[String]] =
      googleStorageAlg
        .unsafeGetBlobBody(googleServicesConfig.googleKeyCacheConfig.bucketName, GcsBlobName(key.value))
    def oldestOf(keys: List[CachedKey]): IO[Option[String]] =
      readFromCache(keys.minBy(_.timeCreated))
    def newestOf(keys: List[CachedKey]): IO[Option[String]] =
      readFromCache(keys.maxBy(_.timeCreated))

    /* segment cached keys into ideal, retired, and nascent keys
        ideal: keys between 15 minutes and 12 days old. Use these whenever possible.
        retired: keys older than 12 days. Avoid if possible, but prefer these over nascent keys.
        nascent: keys newer than 15 minutes. Only use if nothing else is available; these may cause errors due to Google
          eventual consistency.
     */
    val now = Instant.now()
    val retirementTime = now.minusSeconds(googleServicesConfig.googleKeyCacheConfig.activeKeyMaxAge * (24L * 60 * 60))
    val nascentTime = now.minusSeconds(googleServicesConfig.googleKeyCacheConfig.nascentKeyMinAgeMinutes * 60L)

    val (retiredKeys, unretiredKeys) = keysInCache.partition(_.isBefore(retirementTime))
    val (idealKeys, nascentKeys) = unretiredKeys.partition(_.isBefore(nascentTime))

    if (idealKeys.nonEmpty) {
      // if any ideal keys exist, return the newest of those
      newestOf(idealKeys)
    } else if (nascentKeys.nonEmpty && retiredKeys.isEmpty) {
      // if any nascent keys exist but no retired keys exist, return the oldest nascent key
      logger.info(
        s"searchCachedKeys: ${pet.id.project.value}-${pet.serviceAccount.subjectId.value} is using a nascent key"
      )
      oldestOf(nascentKeys)
    } else if (nascentKeys.nonEmpty && retiredKeys.nonEmpty) {
      // if both nascent and retired keys exist, return the newest retired key
      logger.info(
        s"searchCachedKeys: ${pet.id.project.value}-${pet.serviceAccount.subjectId.value} is using a retired key; nascent keys exist"
      )
      newestOf(retiredKeys)
    } else if (!withinLock) {
      // if not within lock, return None. This will signal callers to obtain a lock and re-call this function.
      IO.pure(None)
    } else if (nascentKeys.isEmpty && retiredKeys.nonEmpty) {
      // if within lock, retired keys exist, and no nascent keys exist:
      // trigger key creation and then return newest retired key
      for {
        _ <- cleanupAndCreateKey(pet, keysInCache)
        retiredKey <- newestOf(retiredKeys)
      } yield {
        logger.info(
          s"searchCachedKeys: ${pet.id.project.value}-${pet.serviceAccount.subjectId.value} is using a retired key; no nascent keys exist"
        )
        retiredKey
      }
    } else {
      // no keys exist; create one and return it
      for {
        newKey <- cleanupAndCreateKey(pet, keysInCache)
      } yield {
        logger.info(
          s"searchCachedKeys: ${pet.id.project.value}-${pet.serviceAccount.subjectId.value} is using a just-created key"
        )
        Option(newKey)
      }
    }

  }

  /** Delete a key from IAM and cache.
    */
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

  /** Cleans up keys by deleting orphans and bringing total key count down to 9, then creates a new key.
    */
  private def cleanupAndCreateKey(pet: PetServiceAccount, cachedKeyObjects: List[CachedKey]): IO[String] =
    for {
      keysInIam <- fetchKeysFromIam(pet)
      _ <- cleanupKeys(pet, cachedKeyObjects, keysInIam)
      newKey <- furnishNewKey(pet)
    } yield newKey

  /** Create a new key for this pet. Do not call this directly, as it has no protection against running over the 10-key limit. Call cleanupAndCreateKey()
    * instead.
    */
  private def furnishNewKey(pet: PetServiceAccount): IO[String] =
    for {
      key <- IO.fromFuture(IO(googleIamDAO.createServiceAccountKey(pet.id.project, pet.serviceAccount.email))) recover {
        case e: GoogleJsonResponseException =>
          logger.error(
            s"furnishNewKey: ${pet.id.project.value}-${pet.serviceAccount.subjectId.value} ${e.getMessage}",
            e
          )
          if (e.getDetails.getCode == StatusCodes.TooManyRequests.intValue)
            // 2025-01-29: TooManyRequests is not returned by Google for this error anymore. Leaving this in place
            //  in case Google switches back to it
            throw new WorkbenchException("You have reached the 10 key limit on service accounts. Please remove one to create another.")
          else if (e.getDetails.getCode == StatusCodes.BadRequest.intValue && e.getDetails.getMessage == "Precondition check failed.")
            throw new WorkbenchException("You may have reached the 10 key limit on service accounts.")
          else
            throw new WorkbenchException(s"Error creating key for service account: ${e.getDetails.getCode}: ${e.getDetails.getMessage}")
        case t: Throwable =>
          logger.error(
            s"furnishNewKey: ${pet.id.project.value}-${pet.serviceAccount.subjectId.value} ${t.getMessage}",
            t
          )
          throw new WorkbenchException(s"Error creating key for service account: ${t.getMessage}")
      }
      decodedKey <- IO.fromEither(key.privateKeyData.decode.toRight(new WorkbenchException("Failed to decode retrieved key")))
      _ <- (Stream.emits(decodedKey.getBytes(utf8Charset)).covary[IO] through googleStorageAlg.streamUploadBlob(
        googleServicesConfig.googleKeyCacheConfig.bucketName,
        keyNameFull(pet.id.project, pet.serviceAccount.email, key.id)
      )).compile.drain
    } yield decodedKey

  /** Cleans up keys by deleting orphans and bringing total key count down to 9.
    */
  private def cleanupKeys(pet: PetServiceAccount, cachedKeyObjects: List[CachedKey], serviceAccountKeys: List[ServiceAccountKey]): IO[Unit] = {
    // if 10 or more keys, delete until we are below 10.
    val tooManyFunction: IO[_] = if (cachedKeyObjects.size >= 10) {
      val numToDelete = cachedKeyObjects.size - 9
      val toDelete = cachedKeyObjects.sortBy(_.timeCreated).takeRight(numToDelete)
      logger.info(
        s"cleanupKeys: ${pet.id.project.value}-${pet.serviceAccount.subjectId.value} is reaping ${toDelete.size} keys due to quota"
      )
      toDelete.traverse(cachedKey => removeKey(pet, cachedKey.keyId))
    } else {
      IO.unit
    }

    // if any keys in IAM which are not in cache, delete those orphans.
    val cachedKeyIds: Set[ServiceAccountKeyId] = cachedKeyObjects.map(_.keyId).toSet
    val unknownKeyIds: Set[ServiceAccountKeyId] = serviceAccountKeys.map(_.id).toSet -- cachedKeyIds

    val orphansFunction: IO[_] = if (unknownKeyIds.nonEmpty) {
      logger.info(
        s"cleanupKeys: ${pet.id.project.value}-${pet.serviceAccount.subjectId.value} is reaping ${unknownKeyIds.size} orphaned keys"
      )
      unknownKeyIds.toList.traverse(keyId => IO.fromFuture(IO(googleIamDAO.removeServiceAccountKey(pet.id.project, pet.serviceAccount.email, keyId))))
    } else {
      IO.unit
    }

    for {
      _ <- tooManyFunction
      _ <- orphansFunction
    } yield ()

  }

}
