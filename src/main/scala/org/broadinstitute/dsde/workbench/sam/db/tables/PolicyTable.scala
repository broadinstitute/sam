package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.sam.db.DatabaseId
import org.broadinstitute.dsde.workbench.sam.model.ValueObject
import scalikejdbc._

final case class PolicyId(value: Long) extends DatabaseId
final case class PolicyName(value: String) extends ValueObject
final case class PolicyRecord(id: PolicyId,
                              resourceId: ResourceId,
                              groupId: GroupId,
                              name: PolicyName)

object PolicyRecord extends SQLSyntaxSupport[PolicyRecord] {
  override def tableName: String = "SAM_RESOURCE_POLICY"

  import ResourceRecordBinders._
  import GroupRecordBinders._
  import PolicyRecordBinders._
  def apply(e: ResultName[PolicyRecord])(rs: WrappedResultSet): PolicyRecord = PolicyRecord(
    rs.get(e.id),
    rs.get(e.resourceId),
    rs.get(e.groupId),
    rs.get(e.name)
  )
}

object PolicyRecordBinders {
  implicit val policyIdTypeBinder: TypeBinder[PolicyId] = new TypeBinder[PolicyId] {
    def apply(rs: ResultSet, label: String): PolicyId = PolicyId(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): PolicyId = PolicyId(rs.getLong(index))
  }

  implicit val policyNameTypeBinder: TypeBinder[PolicyName] = new TypeBinder[PolicyName] {
    def apply(rs: ResultSet, label: String): PolicyName = PolicyName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): PolicyName = PolicyName(rs.getString(index))
  }
}
