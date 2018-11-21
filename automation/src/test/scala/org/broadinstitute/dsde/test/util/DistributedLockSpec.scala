package org.broadinstitute.dsde.workbench.test.util

import java.util.concurrent.TimeUnit

import cats.effect.IO
import cats.kernel.Eq
import cats.implicits._
import org.broadinstitute.dsde.workbench.google.GoogleFirestoreInterpreter
import org.broadinstitute.dsde.workbench.google.util.{DistributedLock, DistributedLockConfig, FailToObtainLock}
import org.broadinstitute.dsde.workbench.model.WorkbenchException
import org.scalatest.{AsyncFlatSpec, Matchers}
import org.broadinstitute.dsde.workbench.test.Generators.genLockPath
import org.broadinstitute.dsde.workbench.test.SamConfig.GCS

import scala.collection.JavaConverters._
import scala.concurrent.duration._

class DistributedLockSpec extends AsyncFlatSpec with Matchers {
  implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
  implicit val timer = IO.timer(scala.concurrent.ExecutionContext.global)
  implicit val eqWorkbenchException: Eq[WorkbenchException] =
    (x: WorkbenchException, y: WorkbenchException) =>
      x.getMessage == y.getMessage

  val config = DistributedLockConfig(5 seconds, 5)

  val lockResource: cats.effect.Resource[IO, DistributedLock[IO]] = for {
    db <- GoogleFirestoreInterpreter.firestore[IO](
      GCS.pathToSamTestFirestoreAccountPath
    )
  } yield {
    val googeFireStoreOps = GoogleFirestoreInterpreter[IO](db)
    DistributedLock("samServiceTest", config, googeFireStoreOps)
  }

  "acquireLock" should "succeed if a lock can be retrieved" in {
    val lockPath = genLockPath.sample.get
    val res = lockResource.use { lock => lock.acquireLock(lockPath)}

    res.attempt.map(r => r.isRight shouldBe true).unsafeToFuture()
  }

  it should "fail if there's same lock has already been set within 30 seconds" in {
    val lockPath = genLockPath.sample.get
    val res = lockResource.use { lock =>
      for {
        _ <- lock.acquireLock(lockPath)
        _ <- IO.sleep(2 seconds)
        failed <- lock.acquireLock(lockPath).attempt
      } yield {
        failed.swap.toOption.get.asInstanceOf[FailToObtainLock].getMessage shouldBe(s"can't get lock: $lockPath")
      }
    }
    res.unsafeToFuture()
  }

  "releaseLock" should "remove lockPath" in {
    val lockPath = genLockPath.sample.get
    val res = lockResource.use { dl =>
      for {
        lock <- dl.acquireLock(lockPath)
        _ <- dl.releaseLock(lockPath)
        released <- dl.googleFirestoreOps
          .get(lockPath.collectionName, lockPath.document)
      } yield {
        released.getData.asScala shouldBe (null)
      }
    }

    res.unsafeToFuture()
  }

  "withLock" should "eventually get a lock with max retry" in {
    val lockPath = genLockPath.sample.get
    val collectionNameWithPrefix = lockPath.collectionName.copy(asString = "samServiceTest-" + lockPath.collectionName.asString)
    val lockPathWithPrefix = lockPath.copy(collectionName = collectionNameWithPrefix, expiresIn = 7 seconds)  //fix expiresIn so that we won't be waiting for too long in the unit test
    val res = lockResource.use { lock =>
      for {
        current <- timer.clock.realTime(TimeUnit.MILLISECONDS)
        _ <- lock.acquireLock(lockPathWithPrefix)
        _ <- IO.sleep(2 seconds)
        acquireTime <- lock.withLock(lockPath).use { _ =>
          timer.clock
            .realTime(TimeUnit.MILLISECONDS)
        }
      } yield {
        acquireTime - current should be > lockPathWithPrefix.expiresIn.toMillis
      }
    }

    res.unsafeToFuture()
  }

  it should "release the lock after it's used" in {
    val lockPath = genLockPath.sample.get.copy(expiresIn = 5 seconds) //fix expiresIn so that we won't be waiting for too long in the unit test
    val collectionNameWithPrefix = lockPath.collectionName.copy(asString = "samServiceTest-" + lockPath.collectionName.asString)
    val lockPathWithPrefix = lockPath.copy(collectionName = collectionNameWithPrefix, expiresIn = 7 seconds)  //fix expiresIn so that we won't be waiting for too long in the unit test

    val res = lockResource.use { lock =>
      for {
        currentTime <- timer.clock.monotonic(DistributedLock.EXPIRESATTIMEUNIT)
        lockData <- lock.withLock(lockPath).use{
          _ =>
            lock.googleFirestoreOps.get(collectionNameWithPrefix, lockPathWithPrefix.document)
        }
        released <- lock.googleFirestoreOps
          .get(collectionNameWithPrefix, lockPathWithPrefix.document) //withLock will prefix the lockPrefix in the path
      } yield {
        // validate we actually set and release the same lockPath
        lockData.getLong(DistributedLock.EXPIRESAT).longValue() should be >= (currentTime + lockPath.expiresIn.toMillis)
        released.getData.asScala shouldBe (null)
      }
    }

    res.unsafeToFuture()
  }

  it should "fail to get a lock if max retry is reached" in {
    val lockPath = genLockPath.sample.get
    val collectionNameWithPrefix = lockPath.collectionName.copy(asString = "samServiceTest-" + lockPath.collectionName.asString)
    val lockPathWithPrefix = lockPath.copy(collectionName = collectionNameWithPrefix, expiresIn = 10 seconds)  //fix expiresIn so that we won't be waiting for too long in the unit test

    val config = DistributedLockConfig(1 seconds, 3)
    val lockResource: cats.effect.Resource[IO, DistributedLock[IO]] = for {
      db <- GoogleFirestoreInterpreter.firestore[IO](
        GCS.pathToSamTestFirestoreAccountPath
      )
    } yield {
      val googeFireStoreOps = GoogleFirestoreInterpreter[IO](db)
      DistributedLock("samServiceTest", config, googeFireStoreOps)
    }

    val res = lockResource.use { lock =>
      for {
        current <- timer.clock.realTime(TimeUnit.MILLISECONDS)
        _ <- lock.acquireLock(lockPathWithPrefix)
        failed <- lock
          .withLock(lockPath)
          .use(_ => IO.unit)
          .attempt //this will fail to aquire lock
        endTime <- timer.clock.realTime(TimeUnit.MILLISECONDS)
      } yield {
        failed.swap.toOption.get.asInstanceOf[WorkbenchException].getMessage should startWith (s"Reached max retry:")
        // validate we actually retried certain amount of time
        val requestDuration = endTime - current
        requestDuration should be > ((config.maxRetry - 1) * config.retryInterval.toMillis) //not sure why, but the actual duration seems always slightly shorter than config.maxRetry * config.retryInterval
      }
    }

    res.unsafeToFuture()
  }
}
