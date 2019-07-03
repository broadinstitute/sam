package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._

final case class PolicyActionRecord(policyId: PolicyId,
                                    actionId: ResourceActionId)

object PolicyActionRecord extends SQLSyntaxSupport[PolicyActionRecord] {
  override def tableName: String = "SAM_POLICY_ACTION"

  import PolicyRecordBinders._
  import ResourceActionRecordBinders._
  def apply(e: ResultName[PolicyActionRecord])(rs: WrappedResultSet): PolicyActionRecord = PolicyActionRecord(
    rs.get(e.policyId),
    rs.get(e.actionId)
  )
}
