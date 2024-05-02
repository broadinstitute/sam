package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, databaseEnabledClue, samRequestContext}
import org.broadinstitute.dsde.workbench.sam.matchers.TimeMatchers
import org.broadinstitute.dsde.workbench.sam.model.api.SamLock
import org.broadinstitute.dsde.workbench.sam.{RetryableAnyFreeSpec, TestSupport}
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterEach, OptionValues}
import spray.json.{JsObject, JsString}

import java.util.UUID

class PostgresLockDAOSpec extends RetryableAnyFreeSpec with Matchers with BeforeAndAfterEach with TimeMatchers with OptionValues {
  val dao = new PostgresLockDAO(TestSupport.dbRef, TestSupport.dbRef)
  val description = "Test lock"
  val lockType = "duos"
  val lockDetails: JsObject = JsObject("datasetId" -> JsString("DUOS_123"))
  val lock: SamLock = SamLock(UUID.randomUUID(), description, lockType, lockDetails)

  override protected def beforeEach(): Unit =
    TestSupport.truncateAll

  "PostgresLockDAO" - {
    "create" - {
      "create a lock" in {
        assume(databaseEnabled, databaseEnabledClue)
        dao.create(lock, samRequestContext = samRequestContext).unsafeRunSync() shouldEqual lock
      }
    }
  }
}
