package org.broadinstitute.dsde.workbench.sam.openam

import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.model.ResourceTypeName
import org.scalatest.{BeforeAndAfterEach, FreeSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global

class PostgresAccessPolicyDAOSpec extends FreeSpec with Matchers with BeforeAndAfterEach {
  val dao = new PostgresAccessPolicyDAO(TestSupport.dbRef, TestSupport.blockingEc)

  override protected def beforeEach(): Unit = {
    TestSupport.truncateAll
    super.beforeEach()
  }

  "PostgresAccessPolicyDAO" - {
    "createResourceType" in {
      val resourceType = ResourceTypeName("awesomeType")
      dao.createResourceType(resourceType).unsafeRunSync() shouldEqual resourceType
    }
  }
}
