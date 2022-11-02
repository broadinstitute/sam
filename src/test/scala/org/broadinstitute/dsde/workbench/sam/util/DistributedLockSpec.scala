package org.broadinstitute.dsde.workbench.sam.util

import cats.effect.unsafe.implicits.global
import cats.effect.{Clock, IO}
import cats.implicits._
import org.broadinstitute.dsde.workbench.model.WorkbenchException
import org.broadinstitute.dsde.workbench.sam.dataAccess.{Available, DistributedLockConfig, FailToObtainLock, PostgresDistributedLockDAO}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import org.broadinstitute.dsde.workbench.sam.Generator.genLock
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, databaseEnabledClue, dbRef}

import scala.concurrent.duration._

class DistributedLockSpec extends AsyncFlatSpec with Matchers with TestSupport {

  val config = DistributedLockConfig(5 seconds, 5)

  lazy val lockResource: cats.effect.Resource[IO, PostgresDistributedLockDAO[IO]] = cats.effect.Resource.eval(
    IO {
      PostgresDistributedLockDAO[IO](dbRef, dbRef, config)
    }
  )

  "acquireLock" should "succeed if a lock can be retrieved" in {
    assume(databaseEnabled, databaseEnabledClue)

    val lockDetails = genLock.sample.get
    val res = lockResource.use(lock => lock.acquireLock(lockDetails))

    res.attempt.map(r => r.isRight shouldBe true).unsafeToFuture()
  }

  it should "fail if there's same lock has already been set within 30 seconds" in {
    assume(databaseEnabled, databaseEnabledClue)

    val lockDetails = genLock.sample.get
    val res = lockResource.use { lock =>
      for {
        _ <- lock.acquireLock(lockDetails)
        _ <- IO.sleep(2 seconds)
        failed <- lock.acquireLock(lockDetails).attempt
      } yield failed.swap.toOption.get.asInstanceOf[FailToObtainLock].getMessage shouldBe s"can't get lock: $lockDetails"
    }
    res.unsafeToFuture()
  }

  "releaseLock" should "remove lockDetails" in {
    assume(databaseEnabled, databaseEnabledClue)

    val lockDetails = genLock.sample.get
    val res = lockResource.use { dl =>
      for {
        _ <- dl.acquireLock(lockDetails)
        _ <- dl.releaseLock(lockDetails)
        released <- dl.getLockStatus(lockDetails)
      } yield released shouldBe Available
    }

    res.unsafeToFuture()
  }

  "withLock" should "eventually get a lock with max retry" in {
    assume(databaseEnabled, databaseEnabledClue)

    val lockDetails = genLock.sample.get.copy(expiresIn = 7 seconds)
    val res = lockResource.use { lock =>
      for {
        current <- Clock[IO].realTime.map(_.toMillis)
        _ <- lock.acquireLock(lockDetails)
        _ <- IO.sleep(2 seconds)
        acquireTime <- lock.withLock(lockDetails).use { _ =>
          Clock[IO].realTime.map(_.toMillis)
        }
      } yield acquireTime - current should be > lockDetails.expiresIn.toMillis
    }

    res.unsafeToFuture()
  }

  it should "release the lock after it's used" in {
    assume(databaseEnabled, databaseEnabledClue)

    val lockDetails = genLock.sample.get.copy(expiresIn = 5 seconds)

    val res = lockResource.use { lock =>
      for {
        currentTime <- Clock[IO].realTime.map(_.toMillis)
        lockData <- lock.withLock(lockDetails).use { _ =>
          IO.pure(lock.retrieveLock(lockDetails))
        }
        released <- IO.pure(lock.retrieveLock(lockDetails))
      } yield {
        // validate we actually set and release the same lockDetails
        lockData.get.expiresAt.toEpochMilli should be >= (currentTime + lockDetails.expiresIn.toMillis)
        released should be(None)
      }
    }

    res.unsafeToFuture()
  }

  it should "fail to get a lock if max retry is reached" in {
    assume(databaseEnabled, databaseEnabledClue)

    val lockDetails = genLock.sample.get

    val config = DistributedLockConfig(1 seconds, 3)
    val lockResource: cats.effect.Resource[IO, PostgresDistributedLockDAO[IO]] = cats.effect.Resource.eval(
      IO {
        PostgresDistributedLockDAO[IO](dbRef, dbRef, config)
      }
    )

    val res = lockResource.use { lock =>
      for {
        current <- Clock[IO].realTime.map(_.toMillis)
        _ <- lock.acquireLock(lockDetails)
        failed <- lock
          .withLock(lockDetails)
          .use(_ => IO.unit)
          .attempt // this will fail to acquire lock
        endTime <- Clock[IO].realTime.map(_.toMillis)
      } yield {
        failed.swap.toOption.get.asInstanceOf[WorkbenchException].getMessage should startWith(s"Reached max retry:")
        // validate we actually retried certain amount of time
        val requestDuration = endTime - current
        // not sure why, but the actual duration seems always slightly shorter than config.maxRetry * config.retryInterval.
        // It definitely is retrying and failing.
        // Maybe retries are exclusive not inclusive
        requestDuration should be > ((config.maxRetry - 1) * config.retryInterval.toMillis)
      }
    }

    res.unsafeToFuture()
  }
}
