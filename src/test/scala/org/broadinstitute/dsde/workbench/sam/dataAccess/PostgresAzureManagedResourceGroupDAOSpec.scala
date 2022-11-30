package org.broadinstitute.dsde.workbench.sam.dataAccess

import akka.http.scaladsl.model.StatusCodes
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.model.WorkbenchExceptionWithErrorReport
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, databaseEnabledClue}
import org.broadinstitute.dsde.workbench.sam.{Generator, PropertyBasedTesting, TestSupport}
import org.broadinstitute.dsde.workbench.sam.azure.{BillingProfileId, TenantId}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class PostgresAzureManagedResourceGroupDAOSpec extends AnyFreeSpec with Matchers with BeforeAndAfterEach with PropertyBasedTesting with TestSupport {
  val dao = new PostgresAzureManagedResourceGroupDAO(TestSupport.dbRef, TestSupport.dbRef)

  override protected def beforeEach(): Unit =
    TestSupport.truncateAll

  "PostgresAzureManagedResourceGroupDAO" - {
    "insert, query and delete" in forAll(Generator.genManagedResourceGroup) { mrg =>
      assume(databaseEnabled, databaseEnabledClue)

      val backgroundMrg = mrg.copy(
        mrg.managedResourceGroupCoordinates.copy(tenantId = TenantId(mrg.managedResourceGroupCoordinates.tenantId.value + "_other")),
        BillingProfileId(mrg.billingProfileId.value + "other")
      )

      // insert a background record to make sure our queries are not accidentally without restriction
      dao.insertManagedResourceGroup(backgroundMrg, samRequestContext).unsafeRunSync() shouldBe 1

      // mrg should not be there yet
      dao.getManagedResourceGroupByCoordinates(mrg.managedResourceGroupCoordinates, samRequestContext).unsafeRunSync() shouldBe None
      dao.getManagedResourceGroupByBillingProfileId(mrg.billingProfileId, samRequestContext).unsafeRunSync() shouldBe None

      // insert mrg
      dao.insertManagedResourceGroup(mrg, samRequestContext).unsafeRunSync() shouldBe 1

      // should be able to query
      dao.getManagedResourceGroupByCoordinates(mrg.managedResourceGroupCoordinates, samRequestContext).unsafeRunSync() shouldBe Some(mrg)
      dao.getManagedResourceGroupByBillingProfileId(mrg.billingProfileId, samRequestContext).unsafeRunSync() shouldBe Some(mrg)

      // delete background mrg
      dao.deleteManagedResourceGroup(backgroundMrg.billingProfileId, samRequestContext).unsafeRunSync() shouldBe 1

      // verify did not delete mrg by mistake
      dao.getManagedResourceGroupByCoordinates(mrg.managedResourceGroupCoordinates, samRequestContext).unsafeRunSync() shouldBe Some(mrg)
      dao.getManagedResourceGroupByBillingProfileId(mrg.billingProfileId, samRequestContext).unsafeRunSync() shouldBe Some(mrg)

      // delete mrg
      dao.deleteManagedResourceGroup(mrg.billingProfileId, samRequestContext).unsafeRunSync() shouldBe 1

      // verify deletes
      dao.getManagedResourceGroupByCoordinates(mrg.managedResourceGroupCoordinates, samRequestContext).unsafeRunSync() shouldBe None
      dao.getManagedResourceGroupByBillingProfileId(mrg.billingProfileId, samRequestContext).unsafeRunSync() shouldBe None
    }

    "insertManagedResourceGroup" - {
      "detect duplicate coordinates" in forAll(Generator.genManagedResourceGroup) { mrg =>
        assume(databaseEnabled, databaseEnabledClue)

        val dupCoords = mrg.copy(billingProfileId = BillingProfileId(mrg.billingProfileId.value + "other"))
        dao.insertManagedResourceGroup(mrg, samRequestContext).unsafeRunSync() shouldBe 1
        val error = intercept[WorkbenchExceptionWithErrorReport] {
          dao.insertManagedResourceGroup(dupCoords, samRequestContext).unsafeRunSync()
        }
        error.errorReport.statusCode shouldBe Some(StatusCodes.Conflict)
      }

      "detect duplicate billing profile" in forAll(Generator.genManagedResourceGroup) { mrg =>
        assume(databaseEnabled, databaseEnabledClue)

        val dupBilling = mrg.copy(managedResourceGroupCoordinates =
          mrg.managedResourceGroupCoordinates.copy(tenantId = TenantId(mrg.managedResourceGroupCoordinates.tenantId.value + "_other"))
        )
        dao.insertManagedResourceGroup(mrg, samRequestContext).unsafeRunSync() shouldBe 1
        val error = intercept[WorkbenchExceptionWithErrorReport] {
          dao.insertManagedResourceGroup(dupBilling, samRequestContext).unsafeRunSync()
        }
        error.errorReport.statusCode shouldBe Some(StatusCodes.Conflict)
      }
    }
  }
}
