package org.broadinstitute.dsde.workbench.sam.google

import java.nio.charset.Charset

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import cats.effect.{ContextShift, IO}
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
    val petServiceAccountConfig: PetServiceAccountConfig)(implicit val executionContext: ExecutionContext, cs: ContextShift[IO])
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

    val lockPath = LockPath(CollectionName(s"${pet.id.project.value}-getKey"), Document(pet.serviceAccount.subjectId.value), 20 seconds)
    maybeCreateKey((_, _) =>
      distributedLock.withLock(lockPath).use { _ => maybeCreateKey(cleanupAndCreateKey)
    })
  }

  private def retrieveActiveKey(pet: PetServiceAccount): IO[(Option[String], List[GcsObjectName], List[ServiceAccountKey])] = {
    for {
      keysFromCache <- googleStorageAlg.unsafeListObjectsWithPrefix(googleServicesConfig.googleKeyCacheConfig.bucketName, keyNamePrefix(pet.id.project, pet.serviceAccount.email))
      keysFromIam <- IO.fromFuture(IO(googleIamDAO.listUserManagedServiceAccountKeys(pet.id.project, pet.serviceAccount.email).map(_.toList)))
      maybeMostRecentKey = keysFromCache.sortBy(_.timeCreated.toEpochMilli).lastOption
      mostRecentKey <- maybeMostRecentKey match {
        case Some(mostRecentKey) if isKeyActive(mostRecentKey, keysFromIam) =>
          googleStorageAlg
            .unsafeGetObject(googleServicesConfig.googleKeyCacheConfig.bucketName, GcsBlobName(mostRecentKey.value))

        case _ => IO.pure(None)
      }
    } yield (mostRecentKey, keysFromCache, keysFromIam)
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

  private def furnishNewKey(pet: PetServiceAccount): IO[String] =
    for {
      key <- IO.fromFuture(IO(googleIamDAO.createServiceAccountKey(pet.id.project, pet.serviceAccount.email))) recover {
        case e: GoogleJsonResponseException if e.getDetails.getCode == StatusCodes.TooManyRequests.intValue =>
          throw new WorkbenchException("You have reached the 10 key limit on service accounts. Please remove one to create another.")
      }
      decodedKey <- IO.fromEither(key.privateKeyData.decode.toRight(new WorkbenchException("Failed to decode retrieved key")))
      _ <- googleStorageAlg.storeObject(
        googleServicesConfig.googleKeyCacheConfig.bucketName,
        keyNameFull(pet.id.project, pet.serviceAccount.email, key.id),
        decodedKey.getBytes(utf8Charset),
        "text/plain"
      )
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
