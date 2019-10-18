package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders

final case class AuthDomainRecord(resourceId: ResourcePK,
                                  groupId: GroupPK)

object AuthDomainTable extends SQLSyntaxSupportWithDefaultSamDB[AuthDomainRecord] {
  override def tableName: String = "SAM_RESOURCE_AUTH_DOMAIN"

  import SamTypeBinders._
  def apply(e: ResultName[AuthDomainRecord])(rs: WrappedResultSet): AuthDomainRecord = AuthDomainRecord(
    rs.get(e.resourceId),
    rs.get(e.groupId)
  )
}
