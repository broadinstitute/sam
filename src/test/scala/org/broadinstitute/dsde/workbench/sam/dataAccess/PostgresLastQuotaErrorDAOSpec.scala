package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, databaseEnabledClue}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

class PostgresLastQuotaErrorDAOSpec extends AnyFreeSpec with Matchers with BeforeAndAfterEach {
  val dao = new PostgresLastQuotaErrorDAO(TestSupport.dbRef, TestSupport.dbRef)

  override protected def beforeEach(): Unit =
    TestSupport.truncateAll

  "PostgresLastQuotaErrorDAO" - {
    "quotaErrorOccurredWithinDuration" - {
      "always returns false when no error" in {
        assume(databaseEnabled, databaseEnabledClue)

        dao.quotaErrorOccurredWithinDuration(0.seconds).unsafeRunSync() shouldBe false
        dao.quotaErrorOccurredWithinDuration(10.seconds).unsafeRunSync() shouldBe false
      }

      "returns false when error outside duration" in {
        assume(databaseEnabled, databaseEnabledClue)

        dao.recordQuotaError().unsafeRunSync()
        Thread.sleep(500)
        dao.quotaErrorOccurredWithinDuration(100.millisecond).unsafeRunSync() shouldBe false
      }

      "returns true when error inside duration" in {
        assume(databaseEnabled, databaseEnabledClue)

        dao.recordQuotaError().unsafeRunSync()
        dao.quotaErrorOccurredWithinDuration(200.millisecond).unsafeRunSync() shouldBe true
      }
    }

    "recordQuotaError" - {
      "does not fail on consecutive inserts" in {
        assume(databaseEnabled, databaseEnabledClue)

        dao.recordQuotaError().unsafeRunSync()
        dao.recordQuotaError().unsafeRunSync()
      }
    }
  }
}
