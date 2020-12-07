package org.broadinstitute.dsde.workbench.sam.db.dao

import java.time.Instant

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory.SqlInterpolationWithSamBinders
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.db.{PSQLStateExtensions, SamTypeBinders}
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.sam.model.FullyQualifiedPolicyId
import org.postgresql.util.PSQLException
import scalikejdbc.{DBSession, SQLSyntax}

import scala.util.{Failure, Try}

trait PostgresGroupDAO {
  def insertGroupMembers(groupId: GroupPK, members: Set[WorkbenchSubject])(implicit session: DBSession): Int =
  {
    if (members.isEmpty) {
      0
    } else {
      val memberGroupPKs = queryForGroupPKs(members)
      val memberUserIds = members.collect {
        case userId: WorkbenchUserId => userId
      }.toList

      val insertCount = Try {
        insertGroupMembersIntoHierarchical(groupId, memberGroupPKs, memberUserIds)
      }.recover {
        case duplicateException: PSQLException if duplicateException.getSQLState == PSQLStateExtensions.UNIQUE_VIOLATION => 0
      }.get

      if (insertCount > 0) {
        // if nothing was inserted no need to change the flat structure, it would insert dup records
        insertGroupMembersIntoFlat(groupId, memberGroupPKs, memberUserIds)
      }
      insertCount
    }
  }

  private def insertGroupMembersIntoHierarchical(groupId: GroupPK, memberGroupPKs: List[GroupPK], memberUserIds: List[WorkbenchUserId])(implicit session: DBSession) = {
    val memberUserValues: List[SQLSyntax] = memberUserIds.map {
      case userId: WorkbenchUserId => samsqls"(${groupId}, ${userId}, ${None})"
    }

    val memberGroupValues: List[SQLSyntax] = memberGroupPKs.map { groupPK =>
      samsqls"(${groupId}, ${None}, ${groupPK})"
    }

    val gm = GroupMemberTable.column
    samsql"insert into ${GroupMemberTable.table} (${gm.groupId}, ${gm.memberUserId}, ${gm.memberGroupId}) values ${memberUserValues ++ memberGroupValues}"
      .update().apply()
  }

  private def insertGroupMembersIntoFlat(groupId: GroupPK, memberGroupPKs: List[GroupPK], memberUserIds: List[WorkbenchUserId])(implicit session: DBSession): Unit = {
    val fgmColumn = FlatGroupMemberTable.column
    val fgm = FlatGroupMemberTable.syntax("fgm")

    val directUserValues = memberUserIds.map(uid => samsqls"($uid, cast(null as BIGINT))")
    val directGroupValues = memberGroupPKs.map(gpk => samsqls"(cast(null as varchar), $gpk)")

    // insert direct memberships
    samsql"""insert into ${FlatGroupMemberTable.table} (${fgmColumn.groupId}, ${fgmColumn.memberUserId}, ${fgmColumn.memberGroupId}, ${fgmColumn.groupMembershipPath})
               select ${groupId}, insertValues.member_user_id, insertValues.member_group_id, array[$groupId]
               from (values ${directUserValues ++ directGroupValues}) AS insertValues (member_user_id, member_group_id)""".update().apply()

    // insert memberships where groupId is a subgroup
    samsql"""insert into ${FlatGroupMemberTable.table} (${fgmColumn.groupId}, ${fgmColumn.memberUserId}, ${fgmColumn.memberGroupId}, ${fgmColumn.groupMembershipPath})
             select ${fgm.groupId}, insertValues.member_user_id, insertValues.member_group_id, array_append(${fgm.groupMembershipPath}, $groupId)
             from (values ${directUserValues ++ directGroupValues}) AS insertValues (member_user_id, member_group_id),
             ${FlatGroupMemberTable as fgm}
             where ${fgm.memberGroupId} = $groupId""".update().apply()

    if (memberGroupPKs.nonEmpty) {
      // insert subgroup memberships
      // connect heads and tails where
      // heads have member_group_id in memberGroupPKs - all the paths ending with a member of memberGroupPKs
      // and
      // tails have group_id in memberGroupPKs - all the paths starting with a member of memberGroupPKs
      // the final path is head.groupMembershipPath ++ tail.groupMembershipPath
      // with member ids from the tail and group_id from the head
      val tail = FlatGroupMemberTable.syntax("tail")
      val head = FlatGroupMemberTable.syntax("head")
      samsql"""insert into ${FlatGroupMemberTable.table} (${fgmColumn.groupId}, ${fgmColumn.memberUserId}, ${fgmColumn.memberGroupId}, ${fgmColumn.groupMembershipPath})
             select ${head.groupId}, ${tail.memberUserId}, ${tail.memberGroupId}, array_cat(${head.groupMembershipPath}, ${tail.groupMembershipPath})
             from ${FlatGroupMemberTable as tail}
             join ${FlatGroupMemberTable as head} on ${head.memberGroupId} = ${tail.groupId}
             where ${tail.groupId} in ($memberGroupPKs)
             and ${head.groupMembershipPath}[array_upper(${head.groupMembershipPath}, 1)] = ${groupId}""".update().apply()
    }
  }

  def deleteAllGroupMembers(groupPK: GroupPK)(implicit session: DBSession): Int = {
    val gm = GroupMemberTable.syntax("gm")
    samsql"delete from ${GroupMemberTable as gm} where ${gm.groupId} = ${groupPK}".update().apply()

    val f = FlatGroupMemberTable.syntax("f")
    samsql"delete from ${FlatGroupMemberTable as f} where ${directMembershipClause(groupPK)}".update().apply()
  }

  def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject)(implicit session: DBSession): Boolean = {
    val groupPKQuery = workbenchGroupIdentityToGroupPK(groupId)
    val groupMemberColumn = GroupMemberTable.column

    val removeMemberQuery = removeMember match {
      case memberUser: WorkbenchUserId =>
        samsql"delete from ${GroupMemberTable.table} where ${groupMemberColumn.groupId} = (${groupPKQuery}) and ${groupMemberColumn.memberUserId} = ${memberUser}"
      case memberGroup: WorkbenchGroupIdentity =>
        val memberGroupPKQuery = workbenchGroupIdentityToGroupPK(memberGroup)
        samsql"delete from ${GroupMemberTable.table} where ${groupMemberColumn.groupId} = (${groupPKQuery}) and ${groupMemberColumn.memberGroupId} = (${memberGroupPKQuery})"
      case _ => throw new WorkbenchException(s"unexpected WorkbenchSubject $removeMember")
    }
    val removed = removeMemberQuery.update().apply() > 0

    val f = FlatGroupMemberTable.syntax("f")
    val query =
      samsql"""DELETE FROM ${FlatGroupMemberTable as f}
                     WHERE ${memberClause(removeMember)} AND ${directMembershipClause(groupId)}"""
    query.update().apply()

    removed
  }

  def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject)(implicit session: DBSession): Boolean = {
    val f = FlatGroupMemberTable.syntax("f")
    val query =
      samsql"""SELECT count(*) FROM ${FlatGroupMemberTable as f}
              WHERE ${memberClause(member)} AND ${f.groupId} = (${workbenchGroupIdentityToGroupPK(groupId)})"""
    query.map(rs => rs.int(1)).single().apply().getOrElse(0) > 0
  }

  def updateGroupUpdatedDate(groupId: WorkbenchGroupIdentity)(implicit session: DBSession): Int = {
    val g = GroupTable.column
    samsql"update ${GroupTable.table} set ${g.updatedDate} = ${Instant.now()} where ${g.id} = (${workbenchGroupIdentityToGroupPK(groupId)})".update().apply()
  }

  def deleteGroup(groupName: WorkbenchGroupName)(implicit session: DBSession): Int = {
    val g = GroupTable.syntax("g")

    Try {
      // foreign keys in accessInstructions and groupMember tables are set to cascade delete
      // note: this will not remove this group from any parent groups and will throw a
      // foreign key constraint violation error if group is still a member of any parent groups
      samsql"delete from ${GroupTable as g} where ${g.name} = ${groupName}".update().apply()
    }.recoverWith {
      case fkViolation: PSQLException if fkViolation.getSQLState == PSQLStateExtensions.FOREIGN_KEY_VIOLATION =>
        Failure(new WorkbenchExceptionWithErrorReport(
          ErrorReport(StatusCodes.Conflict, s"group ${groupName.value} cannot be deleted because it is a member of at least 1 other group")))
    }.get

    val f = FlatGroupMemberTable.syntax("f")
    samsql"""delete from ${FlatGroupMemberTable as f}
                where (${GroupTable.groupPKQueryForGroup(groupName)}) = ANY(${f.groupMembershipPath}::int[])""".update().apply()
  }

  private def memberClause(member: WorkbenchSubject): SQLSyntax = {
    val f = FlatGroupMemberTable.syntax("f")
    member match {
      case subGroupId: WorkbenchGroupIdentity => samsqls"${f.memberGroupId} = (${workbenchGroupIdentityToGroupPK(subGroupId)})"
      case WorkbenchUserId(userId) => samsqls"${f.memberUserId} = $userId"
      case _ => throw new WorkbenchException(s"illegal member $member")
    }
  }

  // selection clause for direct membership:
  // choose when the final `groupMembershipPath` array element is equal to groupId
  private def directMembershipClause(groupPK: GroupPK): SQLSyntax = {
    val f = FlatGroupMemberTable.syntax("f")
    samsqls"${f.groupMembershipPath}[array_upper(${f.groupMembershipPath}, 1)] = ${groupPK}"
  }

  // selection clause for direct membership:
  // choose when the final `groupMembershipPath` array element is equal to groupId
  private def directMembershipClause(groupId: WorkbenchGroupIdentity): SQLSyntax = {
    val f = FlatGroupMemberTable.syntax("f")
    samsqls"${f.groupMembershipPath}[array_upper(${f.groupMembershipPath}, 1)] = (${workbenchGroupIdentityToGroupPK(groupId)})"
  }

  protected def queryForGroupPKs(members: Set[WorkbenchSubject])(implicit session: DBSession): List[GroupPK] = {
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
      throw new WorkbenchException(s"Some member groups not found.")
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
}
