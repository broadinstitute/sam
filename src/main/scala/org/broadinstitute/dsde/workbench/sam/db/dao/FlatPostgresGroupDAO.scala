package org.broadinstitute.dsde.workbench.sam.db.dao

import org.broadinstitute.dsde.workbench.model.{WorkbenchSubject, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory.SqlInterpolationWithSamBinders
import org.broadinstitute.dsde.workbench.sam.db.tables.{FlatGroupMemberRecord, FlatGroupMemberTable, FlatGroupMembershipPath, GroupPK}
import scalikejdbc.{DBSession, SQLSyntax, WrappedResultSet, _}

trait FlatPostgresGroupDAO extends PostgresGroupDAO {

  override def insertGroupMembers(groupId: GroupPK, members: Set[WorkbenchSubject])(implicit session: DBSession): Int = {
    if (members.isEmpty) {
      0
    } else {
      val userMembers: Set[WorkbenchUserId] = members.collect { case userId: WorkbenchUserId => userId }
      val groupMembers = queryForGroupPKs(members);

      // direct (non-transitive) membership in groupId
      val groupOnlyPath = FlatGroupMembershipPath(List(groupId));

      val directMemberUsers: List[SQLSyntax] = userMembers.map { userId => {
        samsqls"(${groupId}, ${userId}, ${None}, ${groupOnlyPath})"
      }
      }.toList

      val directMemberGroups: List[SQLSyntax] = groupMembers.map { groupPK =>
        samsqls"(${groupId}, ${None}, ${groupPK}, ${groupOnlyPath})"
      }

      // groups which have groupId as a direct or transitive subgroup
      val ancestorMemberships = listMyGroupRecords(groupId)

      // groups and users which have `groupMembers` as ancestors
      val descendantMemberships = listMembersByPKs(groupMembers)

      val transitiveMembers = ancestorMemberships flatMap { case FlatGroupMemberRecord(_, ancestor, _, _, ancestorPath) =>
        val ancestorsPlusGroup = ancestorPath.append(groupId)

        val ancestorUsers = userMembers.map { userId =>
          samsqls"(${ancestor}, ${userId}, ${None}, ${ancestorsPlusGroup})"
        }.toList

        val ancestorGroups = groupMembers.map { groupPK =>
          samsqls"(${ancestor}, ${None}, ${groupPK}, ${ancestorsPlusGroup})"
        }

        val descendantsWithAncestors = descendantMemberships.collect {
          case FlatGroupMemberRecord(_, _, Some(memberUserId), _, groupMembershipPath) =>
            samsqls"(${ancestor}, ${memberUserId}, ${None}, ${ancestorsPlusGroup.append(groupMembershipPath)})"
          case FlatGroupMemberRecord(_, _, _, Some(memberGroupId), groupMembershipPath) =>
            samsqls"(${ancestor}, ${None}, ${memberGroupId}, ${ancestorsPlusGroup.append(groupMembershipPath)})"
        }

        ancestorUsers ++ ancestorGroups ++ descendantsWithAncestors
      }

      // descendants of this group where this group is the root
      val descendantsWithoutAncestors = descendantMemberships.collect {
        case FlatGroupMemberRecord(_, _, Some(memberUserId), _, groupMembershipPath) =>
          samsqls"(${groupId}, ${memberUserId}, ${None}, ${groupOnlyPath.append(groupMembershipPath)})"
        case FlatGroupMemberRecord(_, _, _, Some(memberGroupId), groupMembershipPath) =>
          samsqls"(${groupId}, ${None}, ${memberGroupId}, ${groupOnlyPath.append(groupMembershipPath)})"
      }


      val gm = FlatGroupMemberTable.column
      samsql"""insert into ${FlatGroupMemberTable.table} (${gm.groupId}, ${gm.memberUserId}, ${gm.memberGroupId}, ${gm.groupMembershipPath})
        values ${directMemberUsers ++ directMemberGroups ++ transitiveMembers ++ descendantsWithoutAncestors}""".update().apply()
    }
  }

  // get all of the groups that group `member` is a member of - directly or transitively
  private def listMyGroupRecords(member: GroupPK)(implicit session: DBSession) = {
    val f = FlatGroupMemberTable.syntax("f")
    val query = samsql"select ${f.resultAll} from ${FlatGroupMemberTable as f} where ${f.memberGroupId} = ${member}"
    query.map(convertToFlatGroupMemberTable(f)).list.apply()
  }

  // get all of the direct or transitive members of all `groups`
  private def listMembersByPKs(groups: Traversable[GroupPK])(implicit session: DBSession) = {
    if (groups.isEmpty)
      List.empty
    else {
      val f = FlatGroupMemberTable.syntax("f")
      val query = samsql"select ${f.resultAll} from ${FlatGroupMemberTable as f} where ${f.groupId} IN (${groups})"
      query.map(convertToFlatGroupMemberTable(f)).list.apply()
    }
  }

  // there is probably some implicit magic which avoids this but I don't know it
  private def convertToFlatGroupMemberTable(f: QuerySQLSyntaxProvider[SQLSyntaxSupport[FlatGroupMemberRecord], FlatGroupMemberRecord])
                                           (rs: WrappedResultSet): FlatGroupMemberRecord = FlatGroupMemberTable.apply(f)(rs)

}
