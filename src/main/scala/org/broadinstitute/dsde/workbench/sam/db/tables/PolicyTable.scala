package org.broadinstitute.dsde.workbench.sam.db.tables

import java.sql.ResultSet

import org.broadinstitute.dsde.workbench.model.ValueObject
import org.broadinstitute.dsde.workbench.sam.db.DatabaseKey
import scalikejdbc._

final case class PolicyKey(value: Long) extends DatabaseKey
final case class PolicyName(value: String) extends ValueObject
final case class PolicyRecord(id: PolicyKey,
                              resourceId: ResourceKey,
                              groupId: GroupKey,
                              name: PolicyName)

object PolicyTable extends SQLSyntaxSupport[PolicyRecord] {
  override def tableName: String = "SAM_RESOURCE_POLICY"

  import ResourceTableBinders._
  import GroupTableBinders._
  import PolicyTableBinders._
  def apply(e: ResultName[PolicyRecord])(rs: WrappedResultSet): PolicyRecord = PolicyRecord(
    rs.get(e.id),
    rs.get(e.resourceId),
    rs.get(e.groupId),
    rs.get(e.name)
  )
}

object PolicyTableBinders {
  implicit val policyIdTypeBinder: TypeBinder[PolicyKey] = new TypeBinder[PolicyKey] {
    def apply(rs: ResultSet, label: String): PolicyKey = PolicyKey(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): PolicyKey = PolicyKey(rs.getLong(index))
  }

  implicit val policyNameTypeBinder: TypeBinder[PolicyName] = new TypeBinder[PolicyName] {
    def apply(rs: ResultSet, label: String): PolicyName = PolicyName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): PolicyName = PolicyName(rs.getString(index))
  }
}
