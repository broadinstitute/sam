package org.broadinstitute.dsde.workbench.sam.openam

import org.broadinstitute.dsde.workbench.model.WorkbenchExceptionWithErrorReport
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.model._
import org.scalatest.{BeforeAndAfterEach, FreeSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global

class PostgresAccessPolicyDAOSpec extends FreeSpec with Matchers with BeforeAndAfterEach {
  val dao = new PostgresAccessPolicyDAO(TestSupport.dbRef, TestSupport.blockingEc)

  override protected def beforeEach(): Unit = {
    TestSupport.truncateAll
    super.beforeEach()
  }

  "PostgresAccessPolicyDAO" - {
    val resourceType = ResourceTypeName("awesomeType")

    "createResourceType" in {
      dao.createResourceType(resourceType).unsafeRunSync() shouldEqual resourceType
    }

    "createResource" - {
      val resource = Resource(resourceType, ResourceId("verySpecialResource"), Set.empty)

      "succeeds when resource type exists" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        dao.createResource(resource).unsafeRunSync() shouldEqual resource
      }

      "returns a WorkbenchExceptionWithErrorReport when a resource with the same name and type already exists" in {
        dao.createResourceType(resourceType).unsafeRunSync()
        dao.createResource(resource).unsafeRunSync()

        intercept[WorkbenchExceptionWithErrorReport] {
          dao.createResource(resource).unsafeRunSync()
        }
      }

      "raises an error when the ResourceType does not exist" is pending

      // Need to create N groups, insert them, then create a Resource object with those groups added to the authDomain
      // field on that Resource, then try to create that Resource
      "can add a resource that has at least 1 Auth Domain" is pending
    }
  }
}
