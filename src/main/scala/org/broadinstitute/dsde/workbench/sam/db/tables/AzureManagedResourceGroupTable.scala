package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.sam.azure._
import org.broadinstitute.dsde.workbench.sam.db.{DatabaseKey, SamTypeBinders}
import scalikejdbc._

final case class ManagedResourceGroupPK(value: Long) extends DatabaseKey
final case class AzureManagedResourceGroupRecord(
    id: ManagedResourceGroupPK,
    tenantId: TenantId,
    subscriptionId: SubscriptionId,
    managedResourceGroupName: ManagedResourceGroupName,
    billingProfileId: BillingProfileId
)

object AzureManagedResourceGroupTable extends SQLSyntaxSupportWithDefaultSamDB[AzureManagedResourceGroupRecord] {
  override def tableName: String = "SAM_AZURE_MANAGED_RESOURCE_GROUP"

  import SamTypeBinders._
  def apply(e: ResultName[AzureManagedResourceGroupRecord])(rs: WrappedResultSet): AzureManagedResourceGroupRecord = AzureManagedResourceGroupRecord(
    rs.get(e.id),
    rs.get(e.tenantId),
    rs.get(e.subscriptionId),
    rs.get(e.managedResourceGroupName),
    rs.get(e.billingProfileId)
  )

  def apply(p: SyntaxProvider[AzureManagedResourceGroupRecord])(rs: WrappedResultSet): AzureManagedResourceGroupRecord = apply(p.resultName)(rs)
}
