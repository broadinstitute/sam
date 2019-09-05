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

      val memberGroups: List[SQLSyntax] = queryForGroupPKs(members).map { groupPK =>
        samsqls"(${groupId}, ${None}, ${groupPK})"
      }

      val gm = GroupMemberTable.column
      samsql"insert into ${GroupMemberTable.table} (${gm.groupId}, ${gm.memberUserId}, ${gm.memberGroupId}) values ${memberUsers ++ memberGroups}"
        .update().apply()
    }
  }

  private def queryForGroupPKs(members: Set[WorkbenchSubject])(implicit session: DBSession): List[GroupPK] = {
    import SamTypeBinders._

    // group PK query
    val memberGroupNames = members.collect {
      case groupName: WorkbenchGroupName => groupName
    }
    val gpk = GroupTable.syntax("g")
    val groupPKStatement = samsqls"""select ${gpk.id} as group_id from ${GroupTable as gpk} where ${gpk.name} in ($memberGroupNames)"""

    // policy group PK query
    val memberPolicyIdTuples = members.collect {
      case policyId: FullyQualifiedPolicyId => samsqls"(${policyId.resource.resourceTypeName}, ${policyId.resource.resourceId}, ${policyId.accessPolicyName})"
    }
    val rt = ResourceTypeTable.syntax("rt")
    val r = ResourceTable.syntax("r")
    val p = PolicyTable.syntax("p")
    val policyGroupPKStatement = samsqls"""select ${p.groupId} as group_id
               from ${ResourceTypeTable as rt}
               join ${ResourceTable as r} on ${rt.id} = ${r.resourceTypeId}
               join ${PolicyTable as p} on ${r.id} = ${p.resourceId}
               where (${rt.name}, ${r.name}, ${p.name}) in ($memberPolicyIdTuples)"""

    // there are 4 scenarios: there are both member groups and policies, only one or the other or neither
    // in the case there are both union both queries
    // if only groups or members then only use only the appropriate query
    // if neither don't make any query
    val subgroupPKQuery = (memberGroupNames.nonEmpty, memberPolicyIdTuples.nonEmpty) match {
      case (true, true) => Option(samsqls"$groupPKStatement union $policyGroupPKStatement")
      case (true, false) => Option(groupPKStatement)
      case (false, true) => Option(policyGroupPKStatement)
      case (false, false) => None
    }

    val memberGroupPKs = subgroupPKQuery.map(x => samsql"$x".map(rs => rs.get[GroupPK]("group_id")).list().apply()).getOrElse(List.empty)

    if (memberGroupPKs.size != memberGroupNames.size + memberPolicyIdTuples.size) {
      throw new WorkbenchException(s"Some member groups not found: ${memberGroupPKs}, ${memberGroupNames}.")
    }
    memberGroupPKs
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
                                    policyTableAlias: String = "p"): SQLSyntax = {
    val rt = ResourceTypeTable.syntax(resourceTypeTableAlias)
    val r = ResourceTable.syntax(resourceTableAlias)
    val p = PolicyTable.syntax(policyTableAlias)
    samsqls"""select ${p.groupId}
              from ${ResourceTypeTable as rt}
              join ${ResourceTable as r} on ${rt.id} = ${r.resourceTypeId}
              join ${PolicyTable as p} on ${r.id} = ${p.resourceId}
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
