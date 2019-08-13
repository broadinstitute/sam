package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders

final case class PolicyActionRecord(resourcePolicyId: PolicyPK,
                                    resourceActionId: ResourceActionPK)

object PolicyActionTable extends SQLSyntaxSupportWithDefaultSamDB[PolicyActionRecord] {
  override def tableName: String = "SAM_POLICY_ACTION"

  import SamTypeBinders._
  def apply(e: ResultName[PolicyActionRecord])(rs: WrappedResultSet): PolicyActionRecord = PolicyActionRecord(
    rs.get(e.resourcePolicyId),
    rs.get(e.resourceActionId)
  )
}
