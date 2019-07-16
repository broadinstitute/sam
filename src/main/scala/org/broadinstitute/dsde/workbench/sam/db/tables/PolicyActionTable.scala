package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders

final case class PolicyActionRecord(policyId: PolicyPK,
                                    actionId: ResourceActionPK)

object PolicyActionTable extends SQLSyntaxSupport[PolicyActionRecord] {
  override def tableName: String = "SAM_POLICY_ACTION"

  import SamTypeBinders._
  def apply(e: ResultName[PolicyActionRecord])(rs: WrappedResultSet): PolicyActionRecord = PolicyActionRecord(
    rs.get(e.policyId),
    rs.get(e.actionId)
  )
}
