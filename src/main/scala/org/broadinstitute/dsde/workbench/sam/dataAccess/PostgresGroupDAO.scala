package org.broadinstitute.dsde.workbench.sam.dataAccess

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.db.PSQLStateExtensions
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._
import org.broadinstitute.dsde.workbench.sam.db.SamTypeBinders._
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.sam.model.FullyQualifiedPolicyId
import org.postgresql.util.PSQLException
import scalikejdbc.DBSession
import scalikejdbc.interpolation.SQLSyntax

import java.time.Instant
import scala.util.{Failure, Try}

/** The sam group model is implemented using 2 tables GroupTable and GroupMemberTable. GroupTable stores the name, email address and other top level information
  * of the group. GroupMemberTable stores member groups and users. Note that this is a recursive structure; groups can contain groups and groups may be members
  * of more than one group. Querying this structure is expensive because it requires recursive queries. The FlatGroupMemberTable is used to shift that burden
  * from read time to write time. FlatGroupMemberTable stores all the members of a group, direct and inherited. This makes membership queries straight forward,
  * less database intensive and fast. FlatGroupMemberTable also stores the path through which a user/group is a member. This information is required to update
  * the flat membership structure without recalculating large swaths when top level groups change.
  *
  * A FlatGroupMemberTable contains: the id of the group containing the member the id of the member user or group the path to the member: an array of group ids
  * indicating the path from group_id to member id starts with group_id, exclusive of the member id last_group_membership_element, the last group is in the path
  * above, is tracked separately so it can be indexed
  *
  * Example database records: group(7795) contains user(userid) group(7798) contains user(userid) group(7801) contains group(7798) and group(7799)
  *
  * testdb=# select * from sam_group_member; id | group_id | member_group_id | member_user_id
  * -------+----------+-----------------+---------------- 15636 | 7795 | | userid 15637 | 7798 | | userid 15638 | 7801 | 7798 | 15639 | 7801 | 7799 |
  *
  * testdb=# select * from sam_group_member_flat; id | group_id | member_group_id | member_user_id | group_membership_path | last_group_membership_element
  * --------+----------+-----------------+----------------+-----------------------+------------------------------ 345985 | 7795 | | userid | {7795} | 7795
  * 345986 | 7798 | | userid | {7798} | 7798 345987 | 7801 | 7798 | | {7801} | 7801 345988 | 7801 | 7799 | | {7801} | 7801 345989 | 7801 | | userid |
  * {7801,7798} | 7798
  *
  * It is crucial that all group updates are in serializable transactions to avoid race conditions when concurrent modifications are made affecting the same
  * group structure.
  */
trait PostgresGroupDAO {
  protected def insertGroupMembers(groupId: GroupPK, members: Set[WorkbenchSubject])(implicit session: DBSession): Int = {
    val memberGroupPKs = queryForGroupPKs(members)
    val memberUserIds = collectUserIds(members)

    insertGroupMemberPKs(groupId, memberGroupPKs, memberUserIds)
  }

  protected def collectUserIds(members: Set[WorkbenchSubject]): List[WorkbenchUserId] =
    members.collect { case userId: WorkbenchUserId =>
      userId
    }.toList

  def verifyNoCycles(groupId: GroupPK, memberGroupPKs: List[GroupPK])(implicit session: DBSession): Unit =
    if (memberGroupPKs.nonEmpty) {
      val gmf = GroupMemberFlatTable.syntax("gmf")
      val g = GroupTable.syntax("g")
      val groupsCausingCycle =
        samsql"""select ${g.result.email}
                 from ${GroupMemberFlatTable as gmf}
                 join ${GroupTable as g} on ${gmf.groupId} = ${g.id}
                 where ${gmf.groupId} in ($memberGroupPKs)
                 and ${gmf.memberGroupId} = $groupId""".map(_.get[WorkbenchEmail](g.resultName.email)).list().apply()

      if (groupsCausingCycle.nonEmpty) {
        throw new WorkbenchExceptionWithErrorReport(
          ErrorReport(StatusCodes.BadRequest, s"Could not add member group(s) ${groupsCausingCycle.mkString("[", ",", "]")} because it would cause a cycle")
        )
      }
    }

  protected def insertGroupMemberPKs(groupId: GroupPK, memberGroupPKs: List[GroupPK], memberUserIds: List[WorkbenchUserId])(implicit session: DBSession): Int =
    if (memberGroupPKs.isEmpty && memberUserIds.isEmpty) {
      0
    } else {
      verifyNoCycles(groupId, memberGroupPKs)

      val insertCount = insertGroupMembersIntoHierarchical(groupId, memberGroupPKs, memberUserIds)

      if (insertCount > 0) {
        // if nothing was inserted no need to change the flat structure, it would insert dup records
        insertGroupMembersIntoFlat(groupId, memberGroupPKs, memberUserIds)
      }
      insertCount
    }

  private def insertGroupMembersIntoHierarchical(groupId: GroupPK, memberGroupPKs: List[GroupPK], memberUserIds: List[WorkbenchUserId])(implicit
      session: DBSession
  ) = {
    val memberUserValues: List[SQLSyntax] = memberUserIds.map { case userId: WorkbenchUserId =>
      samsqls"(${groupId}, ${userId}, ${None})"
    }

    val memberGroupValues: List[SQLSyntax] = memberGroupPKs.map { groupPK =>
      samsqls"(${groupId}, ${None}, ${groupPK})"
    }

    val gm = GroupMemberTable.column
    samsql"""insert into ${GroupMemberTable.table} (${gm.groupId}, ${gm.memberUserId}, ${gm.memberGroupId})
            values ${memberUserValues ++ memberGroupValues}
            on conflict do nothing"""
      .update()
      .apply()
  }

  /** Inserting a user/group into a group requires 2 inserts into the flat group model: 1) insert direct membership - group_id is given group,
    * member_group_id/member_user_id is give member, path contains only given group 2) insert indirect memberships - insert a record for every record where
    * member_group_id is the given group, group_id is the same, member_group_id/member_user_id is the member, path is the same with given group appended
    *
    * Inserting a subgroup into a group requires a third insert to connect the subgroup's lower hierarchy: for all records from GroupMemberFlatTable where
    * group_id is the subgroup id (all paths from the subgroup to a member), call these the tail records join GroupMemberFlatTable where member_group_id is the
    * subgroup id and the last element of the path is the parent group (all paths to the subgroup by way of the parent), call these the head records insert a
    * new record joining these pairs of paths, head to tail: group_id is the head group_id (first element of the path) member id is the tail member id path is
    * the head path + tail path
    *
    * Example: Insert group T into group H. H starts empty but is already a member of groups A and B. T already has member groups X and Y which are empty. The
    * flat group model starts containing: Group | Member Group | Path
    * ------|--------------|------ A | H | {A} B | H | {B} T | X | {T} T | Y | {T}
    *
    * step 1 inserts direct membership of T in H Group | Member Group | Path
    * ------|--------------|------ H | T | {H}
    *
    * step 2 inserts indirect memberships T in A and B Group | Member Group | Path
    * ------|--------------|------ A | T | {A,H} B | T | {B,H}
    *
    * step 3 inserts T's lower group hierarchy so that X and Y are members of H, A and B. The tail records are all of the records above where Group is T: ((T,
    * X, {T}), (T, Y, {T}) The head records are all of the records above where Member Group is T and the last path element is H: ((H, T, {H}), (A, T, {A,H}),
    * (B, T, {B,H})) Group | Member Group | Path
    * ------|--------------|------ H | X | {H,T} H | Y | {H,T} A | X | {A,H,T} A | Y | {A,H,T} B | X | {B,H,T} B | Y | {B,H,T}
    *
    * @param groupId
    *   group being added to
    * @param memberGroupPKs
    *   new member group ids
    * @param memberUserIds
    *   new member user ids
    * @param session
    */
  private def insertGroupMembersIntoFlat(groupId: GroupPK, memberGroupPKs: List[GroupPK], memberUserIds: List[WorkbenchUserId])(implicit
      session: DBSession
  ): Unit = {
    val fgmColumn = GroupMemberFlatTable.column
    val fgm = GroupMemberFlatTable.syntax("fgm")

    val directUserValues = memberUserIds.map(uid => samsqls"($uid, cast(null as BIGINT))")
    val directGroupValues = memberGroupPKs.map(gpk => samsqls"(cast(null as varchar), $gpk)")

    // insert direct memberships
    samsql"""insert into ${GroupMemberFlatTable.table} (${fgmColumn.groupId}, ${fgmColumn.memberUserId}, ${fgmColumn.memberGroupId}, ${fgmColumn.groupMembershipPath}, ${fgmColumn.lastGroupMembershipElement})
               select ${groupId}, insertValues.member_user_id, insertValues.member_group_id, array[$groupId], $groupId
               from (values ${directUserValues ++ directGroupValues}) AS insertValues (member_user_id, member_group_id)""".update().apply()

    // insert memberships where groupId is a subgroup
    samsql"""insert into ${GroupMemberFlatTable.table} (${fgmColumn.groupId}, ${fgmColumn.memberUserId}, ${fgmColumn.memberGroupId}, ${fgmColumn.groupMembershipPath}, ${fgmColumn.lastGroupMembershipElement})
             select ${fgm.groupId}, insertValues.member_user_id, insertValues.member_group_id, array_append(${fgm.groupMembershipPath}, $groupId), $groupId
             from (values ${directUserValues ++ directGroupValues}) AS insertValues (member_user_id, member_group_id),
             ${GroupMemberFlatTable as fgm}
             where ${fgm.memberGroupId} = $groupId""".update().apply()

    if (memberGroupPKs.nonEmpty) {
      // insert subgroup memberships
      val tail = GroupMemberFlatTable.syntax("tail")
      val head = GroupMemberFlatTable.syntax("head")
      samsql"""insert into ${GroupMemberFlatTable.table} (${fgmColumn.groupId}, ${fgmColumn.memberUserId}, ${fgmColumn.memberGroupId}, ${fgmColumn.groupMembershipPath}, ${fgmColumn.lastGroupMembershipElement})
             select ${head.groupId}, ${tail.memberUserId}, ${tail.memberGroupId}, array_cat(${head.groupMembershipPath}, ${tail.groupMembershipPath}), ${tail.groupMembershipPath}[array_upper(${tail.groupMembershipPath}, 1)]
             from ${GroupMemberFlatTable as tail}
             join ${GroupMemberFlatTable as head} on ${head.memberGroupId} = ${tail.groupId}
             where ${tail.groupId} in ($memberGroupPKs)
             and ${head.lastGroupMembershipElement} = ${groupId}""".update().apply()
    }
  }

  def removeAllGroupMembers(groupPK: GroupPK)(implicit session: DBSession): Int = {
    removeAllMembersFromFlatGroup(groupPK)
    removeAllMembersFromHierarchy(groupPK)
  }

  private def removeAllMembersFromHierarchy(groupPK: GroupPK)(implicit session: DBSession) = {
    val gm = GroupMemberTable.syntax("gm")
    samsql"delete from ${GroupMemberTable as gm} where ${gm.groupId} = ${groupPK}".update().apply()
  }

  private def removeAllMembersFromFlatGroup(groupPK: GroupPK)(implicit session: DBSession) = {
    // removing all members means all rows where groupPK has members (groupId = groupPK) or descendants (groupPK in groupMembershipPath)
    val f = GroupMemberFlatTable.syntax("f")
    samsql"delete from ${GroupMemberFlatTable as f} where ${f.groupId} = ${groupPK}".update().apply()
    samsql"delete from ${GroupMemberFlatTable as f} where array_position(${f.groupMembershipPath}, ${groupPK}) is not null".update().apply()
  }

  def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject)(implicit session: DBSession): Boolean =
    removeMember match {
      case memberUser: WorkbenchUserId =>
        removeMemberUserFromFlatGroup(groupId, memberUser)
        removeMemberUserFromHierarchy(groupId, memberUser)

      case memberGroup: WorkbenchGroupIdentity =>
        removeMemberGroupFromFlatGroup(groupId, memberGroup)
        removeMemberGroupFromHierarchy(groupId, memberGroup)

      case _ => throw new WorkbenchException(s"unexpected WorkbenchSubject $removeMember")
    }

  private def removeMemberGroupFromHierarchy(groupId: WorkbenchGroupIdentity, memberGroup: WorkbenchGroupIdentity)(implicit session: DBSession) = {
    val groupMemberColumn = GroupMemberTable.column
    samsql"""delete from ${GroupMemberTable.table}
                where ${groupMemberColumn.groupId} = (${workbenchGroupIdentityToGroupPK(groupId)})
                and ${groupMemberColumn.memberGroupId} = (${workbenchGroupIdentityToGroupPK(memberGroup)})""".update().apply() > 0
  }

  private def removeMemberGroupFromFlatGroup(groupId: WorkbenchGroupIdentity, memberGroup: WorkbenchGroupIdentity)(implicit session: DBSession) = {
    val f = GroupMemberFlatTable.syntax("f")
    // remove rows where memberGroup directly in groupId
    samsql"""delete from ${GroupMemberFlatTable as f}
                where ${f.memberGroupId} = (${workbenchGroupIdentityToGroupPK(memberGroup)})
                and ${f.lastGroupMembershipElement} = (${workbenchGroupIdentityToGroupPK(groupId)})""".update().apply()

    // remove rows where groupId is directly followed by memberGroup in membership path, these are indirect memberships
    // The condition that uses @> is for performance, it allows the query to hit an index. It finds all rows where
    // f.groupMembershipPath contains both the container and member but in no particular order or placement.
    // But we really only want to delete entries where container is immediately before member. However @> uses an index
    // and array_position does not.
    samsql"""with container as (${workbenchGroupIdentityToGroupPK(groupId)}),
             member as (${workbenchGroupIdentityToGroupPK(memberGroup)})
             delete from ${GroupMemberFlatTable as f}
                where array_position(${f.groupMembershipPath}, (select id from container)) + 1 =
                array_position(${f.groupMembershipPath}, (select id from member))
                and ${f.groupMembershipPath} @> array[(select id from container), (select id from member)]""".update().apply()
  }

  private def removeMemberUserFromHierarchy(groupId: WorkbenchGroupIdentity, memberUser: WorkbenchUserId)(implicit session: DBSession) = {
    val groupMemberColumn = GroupMemberTable.column
    samsql"""delete from ${GroupMemberTable.table}
                where ${groupMemberColumn.groupId} = (${workbenchGroupIdentityToGroupPK(groupId)})
                and ${groupMemberColumn.memberUserId} = ${memberUser}""".update().apply() > 0
  }

  private def removeMemberUserFromFlatGroup(groupId: WorkbenchGroupIdentity, memberUser: WorkbenchUserId)(implicit session: DBSession) = {
    val f = GroupMemberFlatTable.syntax("f")
    samsql"""delete from ${GroupMemberFlatTable as f}
                where ${f.memberUserId} = $memberUser
                and ${f.lastGroupMembershipElement} = (${workbenchGroupIdentityToGroupPK(groupId)})""".update().apply()
  }

  def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject)(implicit session: DBSession): Boolean = {
    val f = GroupMemberFlatTable.syntax("f")
    val query =
      samsql"""SELECT count(*) FROM ${GroupMemberFlatTable as f}
              WHERE ${memberClause(member, f)} AND ${f.groupId} = (${workbenchGroupIdentityToGroupPK(groupId)})"""
    query.map(rs => rs.int(1)).single().apply().getOrElse(0) > 0
  }

  def updateGroupUpdatedDate(groupId: WorkbenchGroupIdentity)(implicit session: DBSession): Int = {
    val g = GroupTable.column
    samsql"update ${GroupTable.table} set ${g.updatedDate} = ${Instant.now()} where ${g.id} = (${workbenchGroupIdentityToGroupPK(groupId)})".update().apply()
  }

  def deleteGroup(groupName: WorkbenchGroupName)(implicit session: DBSession): Int = {
    val g = GroupTable.syntax("g")

    val maybeGroupPK = Try {
      // foreign keys in accessInstructions and groupMember tables are set to cascade delete
      // note: this will not remove this group from any parent groups and will throw a
      // foreign key constraint violation error if group is still a member of any parent groups
      samsql"delete from ${GroupTable as g} where ${g.name} = ${groupName} returning ${g.result.id}".map(_.get[GroupPK](g.resultName.id)).single().apply()
    }.recoverWith {
      case fkViolation: PSQLException if fkViolation.getSQLState == PSQLStateExtensions.FOREIGN_KEY_VIOLATION =>
        Failure(
          new WorkbenchExceptionWithErrorReport(
            ErrorReport(StatusCodes.Conflict, s"group ${groupName.value} cannot be deleted because it is a member of at least 1 other group")
          )
        )
    }.get

    maybeGroupPK.foreach(removeAllMembersFromFlatGroup)
    maybeGroupPK.size // this should be 0 or 1
  }

  private def memberClause(
      member: WorkbenchSubject,
      f: scalikejdbc.QuerySQLSyntaxProvider[scalikejdbc.SQLSyntaxSupport[GroupMemberFlatRecord], GroupMemberFlatRecord]
  ): SQLSyntax =
    member match {
      case subGroupId: WorkbenchGroupIdentity => samsqls"${f.memberGroupId} = (${workbenchGroupIdentityToGroupPK(subGroupId)})"
      case WorkbenchUserId(userId) => samsqls"${f.memberUserId} = $userId"
      case _ => throw new WorkbenchException(s"illegal member $member")
    }

  protected def queryForGroupPKs(members: Set[WorkbenchSubject])(implicit session: DBSession): List[GroupPK] = {

    // group PK query
    val memberGroupNames = members.collect { case groupName: WorkbenchGroupName =>
      groupName
    }
    val gpk = GroupTable.syntax("g")
    val groupPKStatement = samsqls"""select ${gpk.id} as group_id from ${GroupTable as gpk} where ${gpk.name} in ($memberGroupNames)"""

    // policy group PK query
    val memberPolicyIdTuples = members.collect { case policyId: FullyQualifiedPolicyId =>
      samsqls"(${policyId.resource.resourceTypeName}, ${policyId.resource.resourceId}, ${policyId.accessPolicyName})"
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

  def workbenchGroupIdentityToGroupPK(groupId: WorkbenchGroupIdentity): SQLSyntax =
    groupId match {
      case group: WorkbenchGroupName => groupPKQueryForGroup(group)
      case policy: FullyQualifiedPolicyId => groupPKQueryForPolicy(policy)
    }

  private def groupPKQueryForGroup(groupName: WorkbenchGroupName, groupTableAlias: String = "gpk"): SQLSyntax = {
    val gpk = GroupTable.syntax(groupTableAlias)
    samsqls"select ${gpk.id} from ${GroupTable as gpk} where ${gpk.name} = $groupName"
  }

  private def groupPKQueryForPolicy(
      policyId: FullyQualifiedPolicyId,
      resourceTypeTableAlias: String = "rt",
      resourceTableAlias: String = "r",
      policyTableAlias: String = "p"
  ): SQLSyntax = {
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
