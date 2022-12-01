package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO
import org.broadinstitute.dsde.workbench.sam.azure.{BillingProfileId, ManagedResourceGroup, ManagedResourceGroupCoordinates}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.collection.mutable

class MockAzureManagedResourceGroupDAO extends AzureManagedResourceGroupDAO {
  val mrgs = new mutable.HashSet[ManagedResourceGroup]
  override def insertManagedResourceGroup(managedResourceGroup: ManagedResourceGroup, samRequestContext: SamRequestContext): IO[Int] =
    IO(mrgs += managedResourceGroup).map(_ => 1)

  override def getManagedResourceGroupByBillingProfileId(
      billingProfileId: BillingProfileId,
      samRequestContext: SamRequestContext
  ): IO[Option[ManagedResourceGroup]] =
    IO(mrgs.find(_.billingProfileId == billingProfileId))

  override def getManagedResourceGroupByCoordinates(
      managedResourceGroupCoordinates: ManagedResourceGroupCoordinates,
      samRequestContext: SamRequestContext
  ): IO[Option[ManagedResourceGroup]] =
    IO(mrgs.find(_.managedResourceGroupCoordinates == managedResourceGroupCoordinates))

  override def deleteManagedResourceGroup(billingProfileId: BillingProfileId, samRequestContext: SamRequestContext): IO[Int] =
    IO(mrgs.filterInPlace(_.billingProfileId != billingProfileId)).map(_ => 1)
}
