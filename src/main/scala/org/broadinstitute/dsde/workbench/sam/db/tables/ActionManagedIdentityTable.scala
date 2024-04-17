package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.sam.azure._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders
import scalikejdbc._

final case class ActionManagedIdentityRecord(
    resourceId: ResourcePK,
    resourceActionId: ResourceActionPK,
    tenantId: TenantId,
    subscriptionId: SubscriptionId,
    managedResourceGroupName: ManagedResourceGroupName,
    objectId: ManagedIdentityObjectId,
    displayName: ManagedIdentityDisplayName
)

object ActionManagedIdentityTable extends SQLSyntaxSupportWithDefaultSamDB[ActionManagedIdentityRecord] {
  override def tableName: String = "SAM_ACTION_MANAGED_IDENTITY"

  import SamTypeBinders._
  def apply(e: ResultName[ActionManagedIdentityRecord])(rs: WrappedResultSet): ActionManagedIdentityRecord = ActionManagedIdentityRecord(
    rs.get(e.resourceId),
    rs.get(e.resourceActionId),
    rs.get(e.tenantId),
    rs.get(e.subscriptionId),
    rs.get(e.managedResourceGroupName),
    rs.get(e.objectId),
    rs.get(e.displayName)
  )

  def apply(p: SyntaxProvider[ActionManagedIdentityRecord])(rs: WrappedResultSet): ActionManagedIdentityRecord = apply(p.resultName)(rs)
}
