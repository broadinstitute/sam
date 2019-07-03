package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._

final case class AuthDomainRecord(resourceId: ResourceId,
                                  groupId: GroupId)

object AuthDomainRecord extends SQLSyntaxSupport[AuthDomainRecord] {
  override def tableName: String = "SAM_RESOURCE_AUTH_DOMAIN"

  import GroupRecordBinders._
  import ResourceRecordBinders._
  def apply(e: ResultName[AuthDomainRecord])(rs: WrappedResultSet): AuthDomainRecord = AuthDomainRecord(
    rs.get(e.resourceId),
    rs.get(e.groupId)
  )
}
