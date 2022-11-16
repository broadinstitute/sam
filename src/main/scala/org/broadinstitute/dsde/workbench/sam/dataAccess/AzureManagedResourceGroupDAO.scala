package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO
import org.broadinstitute.dsde.workbench.sam.azure.{BillingProfileId, ManagedResourceGroup, ManagedResourceGroupCoordinates}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

trait AzureManagedResourceGroupDAO {
  def insertManagedResourceGroup(managedResourceGroup: ManagedResourceGroup, samRequestContext: SamRequestContext): IO[Int]
  def getManagedResourceGroupByBillingProfileId(billingProfileId: BillingProfileId, samRequestContext: SamRequestContext): IO[Option[ManagedResourceGroup]]
  def getManagedResourceGroupByCoordinates(
      managedResourceGroupCoordinates: ManagedResourceGroupCoordinates,
      samRequestContext: SamRequestContext
  ): IO[Option[ManagedResourceGroup]]
  def deleteManagedResourceGroup(billingProfileId: BillingProfileId, samRequestContext: SamRequestContext): IO[Int]
}
