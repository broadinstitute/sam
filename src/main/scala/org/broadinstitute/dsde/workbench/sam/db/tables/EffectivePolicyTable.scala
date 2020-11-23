package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.sam.db.{DatabaseKey, SamTypeBinders}
import scalikejdbc._

final case class EffectivePolicyPK(value: Long) extends DatabaseKey
final case class EffectivePolicyRecord(id: EffectivePolicyPK,
                                       resourceId: ResourcePK,
                                       sourcePolicyId: PolicyPK,
                                       groupId: GroupPK,
                                       public: Boolean)

object EffectivePolicyTable extends SQLSyntaxSupportWithDefaultSamDB[EffectivePolicyRecord] {
  override def tableName: String = "SAM_EFFECTIVE_RESOURCE_POLICY"

  import SamTypeBinders._
  def apply(e: ResultName[EffectivePolicyRecord])(rs: WrappedResultSet): EffectivePolicyRecord = EffectivePolicyRecord(
    rs.get(e.id),
    rs.get(e.resourceId),
    rs.get(e.sourcePolicyId),
    rs.get(e.groupId),
    rs.get(e.public)
  )
}
