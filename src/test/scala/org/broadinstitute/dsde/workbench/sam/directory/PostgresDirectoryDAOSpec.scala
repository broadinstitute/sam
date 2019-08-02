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
  }
}
