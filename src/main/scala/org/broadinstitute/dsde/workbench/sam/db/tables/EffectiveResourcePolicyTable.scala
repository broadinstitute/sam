package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.sam.db.{DatabaseKey, SamTypeBinders}
import scalikejdbc._

final case class EffectiveResourcePolicyPK(value: Long) extends DatabaseKey
final case class EffectiveResourcePolicyRecord(id: EffectiveResourcePolicyPK, resourceId: ResourcePK, sourcePolicyId: PolicyPK)

object EffectiveResourcePolicyTable extends SQLSyntaxSupportWithDefaultSamDB[EffectiveResourcePolicyRecord] {
  override def tableName: String = "SAM_EFFECTIVE_RESOURCE_POLICY"

  import SamTypeBinders._
  def apply(e: ResultName[EffectiveResourcePolicyRecord])(rs: WrappedResultSet): EffectiveResourcePolicyRecord = EffectiveResourcePolicyRecord(
    rs.get(e.id),
    rs.get(e.resourceId),
    rs.get(e.sourcePolicyId)
  )
}
