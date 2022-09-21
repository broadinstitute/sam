package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.azure._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders
import scalikejdbc._

final case class PetManagedIdentityRecord(
    userId: WorkbenchUserId,
    tenantId: TenantId,
    subscriptionId: SubscriptionId,
    managedResourceGroupName: ManagedResourceGroupName,
    objectId: ManagedIdentityObjectId,
    displayName: ManagedIdentityDisplayName
)

object PetManagedIdentityTable extends SQLSyntaxSupportWithDefaultSamDB[PetManagedIdentityRecord] {
  override def tableName: String = "SAM_PET_MANAGED_IDENTITY"

  import SamTypeBinders._
  def apply(e: ResultName[PetManagedIdentityRecord])(rs: WrappedResultSet): PetManagedIdentityRecord = PetManagedIdentityRecord(
    rs.get(e.userId),
    rs.get(e.tenantId),
    rs.get(e.subscriptionId),
    rs.get(e.managedResourceGroupName),
    rs.get(e.objectId),
    rs.get(e.displayName)
  )

  def apply(p: SyntaxProvider[PetManagedIdentityRecord])(rs: WrappedResultSet): PetManagedIdentityRecord = apply(p.resultName)(rs)
}
