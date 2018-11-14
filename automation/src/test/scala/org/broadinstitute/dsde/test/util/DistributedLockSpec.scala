package org.broadinstitute.dsde.workbench.test.util

import java.util.concurrent.TimeUnit

import cats.effect.IO
import cats.kernel.Eq
import cats.implicits._
import org.broadinstitute.dsde.workbench.google.GoogleFirestoreOpsInterpreters
import org.broadinstitute.dsde.workbench.google.util.{DistributedLock, DistributedLockConfig}
import org.broadinstitute.dsde.workbench.model.WorkbenchException
import org.scalatest.{AsyncFlatSpec, Matchers}
import org.broadinstitute.dsde.workbench.test.Generators.genLockPath
import org.broadinstitute.dsde.workbench.test.SamConfig.GCS

import scala.collection.JavaConverters._
import scala.concurrent.duration._

class DistributedLockSpec extends AsyncFlatSpec with Matchers {
  implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
  implicit val timer = IO.timer(scala.concurrent.ExecutionContext.global)
  implicit val eqWorkbenchException: Eq[WorkbenchException] = (x: WorkbenchException, y: WorkbenchException) => x.getMessage == y.getMessage

  val config = DistributedLockConfig(5 seconds, 5)

  val lockResource: cats.effect.Resource[IO, DistributedLock[IO]] = for{
    db <- GoogleFirestoreOpsInterpreters.firestore[IO](GCS.pathToSamTestFirestoreAccountPath)
  } yield {
    val googeFireStoreOps = GoogleFirestoreOpsInterpreters.ioFirestore(db)
    DistributedLock("samServiceTest", config, googeFireStoreOps)
  }

  "config file" should "exist" in {
    new java.io.File(GCS.pathToSamTestFirestoreAccountPath).exists shouldBe true
    new java.io.File(GCS.pathToSamTestFirestoreAccountPath+"2").exists shouldBe false
  }

  "acquireLock" should "succeed if a lock can be retrieved" in {
    val lockPath = genLockPath.sample.get
    val res = lockResource.use { lock =>
      lock.acquireLock(lockPath)
    }

    res.attempt.map(r => r.isRight shouldBe true).unsafeToFuture()
  }

  it should "fail if there's same lock has already been set within 30 seconds" in {
    val lockPath = genLockPath.sample.get
    val res = lockResource.use { lock =>
      for{
        _ <- lock.acquireLock(lockPath)
        _ <- IO.sleep(2 seconds)
        _ <- lock.acquireLock(lockPath)
      } yield ()
    }
    //if there's no === in scalatest, we could've done `r === Left(new WorkbenchException("can't get lock")`
    res.attempt.map(r => Eq[WorkbenchException].eqv(r.swap.toOption.get.asInstanceOf[WorkbenchException], new WorkbenchException(s"can't get lock $lockPath")) shouldBe(true)).unsafeToFuture()
  }


  "releaseLock" should "set isLocked to false" in {
    val lockPath = genLockPath.sample.get
    val res = lockResource.use { dl =>
      for{
        lock <- dl.acquireLock(lockPath)
        _ <- dl.releaseLock(lockPath)
        released <- dl.googleFirestoreOps.get(lockPath.collectionName, lockPath.document)
      } yield {
        released.getData.asScala shouldBe(null)
      }
    }

    res.unsafeToFuture()
  }

  "withLock" should "eventually get a lock with max retry" in {
    val lockPath = genLockPath.sample.get.copy(expiresIn = 7 seconds) //fix expiresIn so that we won't be waiting for too long in the unit test
    val res = lockResource.use { lock =>
      for{
        current <- timer.clock.realTime(TimeUnit.MILLISECONDS)
        _ <- lock.acquireLock(lockPath)
        _ <- IO.sleep(2 seconds)
        aquireTime <- lock.withLock(lockPath).use{
          _ =>
            timer.clock.realTime(TimeUnit.MILLISECONDS).flatMap(aquireTime => IO.pure(aquireTime))
        }
      } yield {
        (aquireTime - current - lockPath.expiresIn.toMillis > 0) shouldBe true
      }
    }

    res.attempt.map(r => r.isRight shouldBe true).unsafeToFuture()
  }

  "withLock" should "fail to get a lock if max retry is reached" in {
    val lockPath = genLockPath.sample.get.copy(expiresIn = 10 seconds) //fix expiresIn so that we won't be waiting for too long in the unit test
    val config = DistributedLockConfig(1 seconds, 2)
    val lockResource: cats.effect.Resource[IO, DistributedLock[IO]] = for{
      db <- GoogleFirestoreOpsInterpreters.firestore[IO](GCS.pathToSamTestFirestoreAccountPath)
    } yield {
      val googeFireStoreOps = GoogleFirestoreOpsInterpreters.ioFirestore(db)
      DistributedLock("samServiceTest", config, googeFireStoreOps)
    }

    val res = lockResource.use { lock =>
      for{
        current <- timer.clock.realTime(TimeUnit.MILLISECONDS)
        _ <- lock.acquireLock(lockPath)
        _ <- lock.withLock(lockPath).use(_ => IO.unit).attempt //this will fail to aquire lock
        endTime <- timer.clock.realTime(TimeUnit.MILLISECONDS)
      } yield {
        // validate we actually retried certain amount of time
        (endTime - current - config.maxRetry * config.retryInterval.toMillis > 0) shouldBe true
      }
    }

    res.attempt.map(r => r.isRight shouldBe true).unsafeToFuture()
  }
}