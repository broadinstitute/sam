package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.sam.db.{DatabaseKey, SamTypeBinders}
import org.broadinstitute.dsde.workbench.sam.model.AccessPolicyName
import scalikejdbc._

final case class PolicyPK(value: Long) extends DatabaseKey
final case class PolicyRecord(id: PolicyPK, resourceId: ResourcePK, groupId: GroupPK, name: AccessPolicyName, public: Boolean)

object PolicyTable extends SQLSyntaxSupportWithDefaultSamDB[PolicyRecord] {
  override def tableName: String = "SAM_RESOURCE_POLICY"

  import SamTypeBinders._
  def apply(e: ResultName[PolicyRecord])(rs: WrappedResultSet): PolicyRecord = PolicyRecord(
    rs.get(e.id),
    rs.get(e.resourceId),
    rs.get(e.groupId),
    rs.get(e.name),
    rs.get(e.public)
  )
}
