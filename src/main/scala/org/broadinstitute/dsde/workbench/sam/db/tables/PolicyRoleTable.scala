package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._

final case class PolicyRoleRecord(policyId: PolicyId,
                                  roleId: ResourceRoleId)

object PolicyRoleRecord extends SQLSyntaxSupport[PolicyRoleRecord] {
  override def tableName: String = "SAM_POLICY_ROLE"

  import PolicyRecordBinders._
  import ResourceRoleRecordBinders._
  def apply(e: ResultName[PolicyRoleRecord])(rs: WrappedResultSet): PolicyRoleRecord = PolicyRoleRecord(
    rs.get(e.policyId),
    rs.get(e.roleId)
  )
}
