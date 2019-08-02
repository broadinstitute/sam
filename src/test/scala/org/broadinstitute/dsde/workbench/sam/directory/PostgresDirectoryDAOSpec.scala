package org.broadinstitute.dsde.workbench.sam.directory

import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.openam.PostgresAccessPolicyDAO
import org.scalatest.{BeforeAndAfterEach, FreeSpec}

import scala.concurrent.ExecutionContext.Implicits.global

class PostgresDirectoryDAOSpec extends FreeSpec with BeforeAndAfterEach with DirectoryDAOBehaviors {
  val dao = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.blockingEc)
  val accessPolicyDAO = new PostgresAccessPolicyDAO(TestSupport.dbRef, TestSupport.blockingEc)

  override protected def beforeEach(): Unit = {
    TestSupport.truncateAll
  }

  "PostgresDirectoryDAO" - {
    "should" - {
      behave like directoryDAO(dao, accessPolicyDAO)
    }

    "enableIdentity and disableIdentity" - {
      "cannot enable and disable pet service accounts" in {
        dao.createUser(defaultUser).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA).unsafeRunSync()
        val initialEnabledStatus = dao.isEnabled(defaultPetSA.id).unsafeRunSync()

        dao.disableIdentity(defaultPetSA.id).unsafeRunSync()
        dao.isEnabled(defaultPetSA.id).unsafeRunSync() shouldBe initialEnabledStatus

        dao.enableIdentity(defaultPetSA.id).unsafeRunSync()
        dao.isEnabled(defaultPetSA.id).unsafeRunSync() shouldBe initialEnabledStatus
      }

      "cannot enable and disable groups" in {
        dao.createGroup(defaultGroup).unsafeRunSync()
        val initialEnabledStatus = dao.isEnabled(defaultGroup.id).unsafeRunSync()

        dao.disableIdentity(defaultGroup.id).unsafeRunSync()
        dao.isEnabled(defaultGroup.id).unsafeRunSync() shouldBe initialEnabledStatus

        dao.enableIdentity(defaultGroup.id).unsafeRunSync()
        dao.isEnabled(defaultGroup.id).unsafeRunSync() shouldBe initialEnabledStatus
      }

      "cannot enable and disable policies" is pending
    }


    "isEnabled" - {
      "gets a pet's user's enabled status" in {
        dao.createUser(defaultUser).unsafeRunSync()
        dao.createPetServiceAccount(defaultPetSA).unsafeRunSync()
        dao.isEnabled(defaultPetSA.id).unsafeRunSync() shouldBe false

        dao.enableIdentity(defaultUser.id).unsafeRunSync()
        dao.isEnabled(defaultPetSA.id).unsafeRunSync() shouldBe true

        dao.disableIdentity(defaultUser.id).unsafeRunSync()
        dao.isEnabled(defaultPetSA.id).unsafeRunSync() shouldBe false
      }

      "returns false for groups" in {
        dao.createGroup(defaultGroup).unsafeRunSync()

        dao.isEnabled(defaultGroup.id).unsafeRunSync() shouldBe false
        dao.enableIdentity(defaultGroup.id).unsafeRunSync()
        dao.isEnabled(defaultGroup.id).unsafeRunSync() shouldBe false
      }

      "returns false for policies" is pending
    }
  }
}
