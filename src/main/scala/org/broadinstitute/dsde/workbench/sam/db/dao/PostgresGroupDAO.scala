package org.broadinstitute.dsde.workbench.sam.db.dao
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders
import org.broadinstitute.dsde.workbench.sam.db.tables._
import scalikejdbc.{DBSession, SQLSyntax, SQLSyntaxSupport}
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory.SqlInterpolationWithSamBinders
import org.broadinstitute.dsde.workbench.sam.model.FullyQualifiedPolicyId

trait PostgresGroupDAO {
  def insertGroupMembers(groupId: GroupPK, members: Set[WorkbenchSubject])(implicit session: DBSession): Int = {
    if (members.isEmpty) {
      0
    } else {
      val memberUsers: List[SQLSyntax] = members.collect {
        case userId: WorkbenchUserId => samsqls"(${groupId}, ${userId}, ${None})"
      }.toList

      val memberGroupPKQueries = members.collect {
        case id: WorkbenchGroupIdentity => samsqls"(${workbenchGroupIdentityToGroupPK(id)})"
      }

      import SamTypeBinders._
      // TODO: is there a way to do this without needing N subqueries?
      val memberGroupPKs: List[GroupPK] = if (memberGroupPKQueries.nonEmpty) {
        val g = GroupTable.syntax("g")
        samsql"select ${g.result.id} from ${GroupTable as g} where ${g.id} in (${memberGroupPKQueries})"
          .map(rs => rs.get[GroupPK](g.resultName.id)).list().apply()
      } else {
        List.empty
      }

      val memberGroups: List[SQLSyntax] = memberGroupPKs.map { groupPK =>
        samsqls"(${groupId}, ${None}, ${groupPK})"
      }

      if (memberGroups.size != memberGroupPKQueries.size) {
        throw new WorkbenchException(s"Some member groups not found.")
      } else {
        val gm = GroupMemberTable.column
        samsql"insert into ${GroupMemberTable.table} (${gm.groupId}, ${gm.memberUserId}, ${gm.memberGroupId}) values ${memberUsers ++ memberGroups}"
          .update().apply()
      }
    }
  }

  def workbenchGroupIdentityToGroupPK(groupId: WorkbenchGroupIdentity): SQLSyntax = {
    groupId match {
      case group: WorkbenchGroupName => GroupTable.groupPKQueryForGroup(group)
      case policy: FullyQualifiedPolicyId => groupPKQueryForPolicy(policy)
    }
  }

  private def groupPKQueryForPolicy(policyId: FullyQualifiedPolicyId,
                                    resourceTypeTableAlias: String = "rt",
                                    resourceTableAlias: String = "r",
                                    policyTableAlias: String = "p",
                                    groupTableAlias: String = "g"): SQLSyntax = {
    val rt = ResourceTypeTable.syntax(resourceTypeTableAlias)
    val r = ResourceTable.syntax(resourceTableAlias)
    val p = PolicyTable.syntax(policyTableAlias)
    val g = GroupTable.syntax(groupTableAlias)
    samsqls"""select ${p.groupId}
              from ${ResourceTypeTable as rt}
              join ${ResourceTable as r} on ${rt.id} = ${r.resourceTypeId}
              join ${PolicyTable as p} on ${r.id} = ${p.resourceId}
              join ${GroupTable as g} on ${g.id} = ${p.groupId}
              where ${rt.name} = ${policyId.resource.resourceTypeName}
              and ${r.name} = ${policyId.resource.resourceId}
              and ${p.name} = ${policyId.accessPolicyName}"""
  }

  /**
    * Produces a SQLSyntax to traverse a nested group structure and find all members.
    * Can only be used within a WITH RECURSIVE clause. This is recursive in the SQL sense, it does not call itself.
    *
    * @param groupId the group id to traverse from
    * @param subGroupMemberTable table that will be defined in the WITH clause, must have a unique name within the WITH clause
    * @param groupMemberTableAlias
    * @return
    */
  def recursiveMembersQuery(groupId: WorkbenchGroupIdentity, subGroupMemberTable: SubGroupMemberTable, groupMemberTableAlias: String = "gm"): SQLSyntax = {
    val sg = subGroupMemberTable.syntax("sg")
    val gm = GroupMemberTable.syntax(groupMemberTableAlias)
    val sgColumns = subGroupMemberTable.column
    samsqls"""${subGroupMemberTable.table}(${sgColumns.parentGroupId}, ${sgColumns.memberGroupId}, ${sgColumns.memberUserId}) AS (
          ${directMembersQuery(groupId, groupMemberTableAlias)}
          UNION
          SELECT ${gm.groupId}, ${gm.memberGroupId}, ${gm.memberUserId}
          FROM ${subGroupMemberTable as sg}, ${GroupMemberTable as gm}
          WHERE ${gm.groupId} = ${sg.memberGroupId}
        )"""
  }

  private def directMembersQuery(groupId: WorkbenchGroupIdentity, groupMemberTableAlias: String = "gm"): SQLSyntax = {
    val gm = GroupMemberTable.syntax(groupMemberTableAlias)
    samsqls"""select ${gm.groupId}, ${gm.memberGroupId}, ${gm.memberUserId}
               from ${GroupMemberTable as gm}
               where ${gm.groupId} = (${workbenchGroupIdentityToGroupPK(groupId)})"""
  }
}

// these 2 case classes represent the logical table used in nested group queries
// this table does not actually exist but looks like a table in a WITH RECURSIVE query
final case class SubGroupMemberRecord(parentGroupId: GroupPK, memberUserId: Option[WorkbenchUserId], memberGroupId: Option[GroupPK])
final case class SubGroupMemberTable(override val tableName: String) extends SQLSyntaxSupport[SubGroupMemberRecord] {
  // need to specify column names explicitly because this table does not actually exist in the database
  override val columnNames: Seq[String] = Seq("parent_group_id", "member_user_id", "member_group_id")
}
