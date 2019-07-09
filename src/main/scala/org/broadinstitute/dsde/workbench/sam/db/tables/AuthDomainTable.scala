package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._

final case class AuthDomainRecord(resourceId: ResourceId,
                                  groupId: GroupId)

object AuthDomainTable extends SQLSyntaxSupport[AuthDomainRecord] {
  override def tableName: String = "SAM_RESOURCE_AUTH_DOMAIN"

  import GroupTableBinders._
  import ResourceTableBinders._
  def apply(e: ResultName[AuthDomainRecord])(rs: WrappedResultSet): AuthDomainRecord = AuthDomainRecord(
    rs.get(e.resourceId),
    rs.get(e.groupId)
  )
}
