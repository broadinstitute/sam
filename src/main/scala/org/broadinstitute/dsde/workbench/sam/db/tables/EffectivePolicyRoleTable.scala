package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders

final case class EffectivePolicyRoleRecord(effectiveResourcePolicyId: EffectiveResourcePolicyPK, resourceRoleId: ResourceRolePK)

object EffectivePolicyRoleTable extends SQLSyntaxSupportWithDefaultSamDB[EffectivePolicyRoleRecord] {
  override def tableName: String = "SAM_EFFECTIVE_POLICY_ROLE"

  import SamTypeBinders._
  def apply(e: ResultName[EffectivePolicyRoleRecord])(rs: WrappedResultSet): EffectivePolicyRoleRecord = EffectivePolicyRoleRecord(
    rs.get(e.effectiveResourcePolicyId),
    rs.get(e.resourceRoleId)
  )
}
