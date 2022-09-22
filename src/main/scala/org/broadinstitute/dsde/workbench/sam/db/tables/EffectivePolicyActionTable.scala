package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders

final case class EffectivePolicyActionRecord(effectiveResourcePolicyId: EffectiveResourcePolicyPK, resourceActionId: ResourceActionPK)

object EffectivePolicyActionTable extends SQLSyntaxSupportWithDefaultSamDB[EffectivePolicyActionRecord] {
  override def tableName: String = "SAM_EFFECTIVE_POLICY_ACTION"

  import SamTypeBinders._
  def apply(e: ResultName[EffectivePolicyActionRecord])(rs: WrappedResultSet): EffectivePolicyActionRecord = EffectivePolicyActionRecord(
    rs.get(e.effectiveResourcePolicyId),
    rs.get(e.resourceActionId)
  )
}
