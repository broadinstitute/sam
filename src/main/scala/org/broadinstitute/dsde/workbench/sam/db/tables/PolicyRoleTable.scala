package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders

final case class PolicyRoleRecord(resourcePolicyId: PolicyPK,
                                  resourceRoleId: ResourceRolePK)

object PolicyRoleTable extends SQLSyntaxSupport[PolicyRoleRecord] {
  override def tableName: String = "SAM_POLICY_ROLE"

  import SamTypeBinders._
  def apply(e: ResultName[PolicyRoleRecord])(rs: WrappedResultSet): PolicyRoleRecord = PolicyRoleRecord(
    rs.get(e.resourcePolicyId),
    rs.get(e.resourceRoleId)
  )
}
