package org.broadinstitute.dsde.workbench.sam.directory

import akka.http.scaladsl.model.StatusCodes
import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchException, WorkbenchExceptionWithErrorReport, WorkbenchGroupIdentity, WorkbenchGroupName, WorkbenchSubject, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory.SqlInterpolationWithSamBinders
import org.broadinstitute.dsde.workbench.sam.db._
import org.broadinstitute.dsde.workbench.sam.db.tables.{FlatGroupMemberRecord, FlatGroupMemberTable, FlatGroupMembershipPath, GroupPK, GroupTable, PolicyTable, ResourceTable, ResourceTypeTable}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.postgresql.util.PSQLException
import scalikejdbc._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Try}

/**
  * Replace the "classic" hierarchical/recursive model with a flattened DB structure optimized for quick reads.
  *
  * For the design, see Appendix A in https://docs.google.com/document/d/1pXAhic_GxM-G9qBFTk0JiTF9YY4nUJfdUEWvbkd_rNw
  */
class FlatPostgresDirectoryDAO (override val dbRef: DbReference, override val ecForDatabaseIO: ExecutionContext)
                               (implicit override val cs: ContextShift[IO]) extends PostgresDirectoryDAO(dbRef, ecForDatabaseIO) {

  override def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] = {
    val f = FlatGroupMemberTable.syntax("f")
    runInTransaction("isGroupMember", samRequestContext)({ implicit session =>
      val query =
        samsql"""SELECT count(*) FROM ${FlatGroupMemberTable as f}
                WHERE ${memberClause(member)} AND ${f.groupId} = ${workbenchGroupIdentityToGroupPK(groupId)}"""
      query.map(rs => rs.int(1)).single().apply().getOrElse(0) > 0
    })
  }

  override def addGroupMember(groupId: WorkbenchGroupIdentity, addMember: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] = {
    runInTransaction("addGroupMember", samRequestContext)({ implicit session =>
      insertGroupMembers(queryForGroupPKs(Set(groupId)).head, Set(addMember))
    }).map(count => count > 0)
  }

  override def insertGroupMembers(groupId: GroupPK, members: Set[WorkbenchSubject])(implicit session: DBSession): Int = {
    if (members.isEmpty) {
      0
    } else {
      val userMembers: Set[WorkbenchUserId] = members.collect { case userId: WorkbenchUserId => userId }
      val groupMembers = queryForGroupPKs(members);

      // direct (non-transitive) membership in groupId
      val directMembershipPath = FlatGroupMembershipPath(List(groupId));

      val directMemberUsers: List[SQLSyntax] = userMembers.map { userId => {
        samsqls"(${groupId}, ${userId}, ${None}, ${directMembershipPath})"
      }
      }.toList

      val directMemberGroups: List[SQLSyntax] = groupMembers.map { groupPK =>
        samsqls"(${groupId}, ${None}, ${groupPK}, ${directMembershipPath})"
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

        val descendants = descendantMemberships.collect {
          case FlatGroupMemberRecord(_, _, Some(memberUserId), _, groupMembershipPath) =>
            samsqls"(${ancestor}, ${memberUserId}, ${None}, ${ancestorsPlusGroup.append(groupMembershipPath)})"
          case FlatGroupMemberRecord(_, _, _, Some(memberGroupId), groupMembershipPath) =>
            samsqls"(${ancestor}, ${None}, ${memberGroupId}, ${ancestorsPlusGroup.append(groupMembershipPath)})"
        }

        ancestorUsers ++ ancestorGroups ++ descendants
      }

      val gm = FlatGroupMemberTable.column
      samsql"""insert into ${FlatGroupMemberTable.table} (${gm.groupId}, ${gm.memberUserId}, ${gm.memberGroupId}, ${gm.groupMembershipPath})
        values ${directMemberUsers ++ directMemberGroups ++ transitiveMembers}""".update().apply()
    }
  }

  override def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] = {
    val f = FlatGroupMemberTable.syntax("f")

    // remove all records where `groupId` is the direct parent of `removeMember`
    runInTransaction("removeGroupMember", samRequestContext)({ implicit session =>
      val query =
        samsql"""DELETE FROM ${FlatGroupMemberTable as f}
                     WHERE ${memberClause(removeMember)} AND ${directMembershipGroupClause(groupId)}"""
      query.update().apply()
    }).map(count => count > 0)
  }

  override def deleteGroup(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Unit] = {
    runInTransaction("deleteGroup", samRequestContext)({ implicit session =>
      val f = FlatGroupMemberTable.syntax("f")
      val g = GroupTable.syntax("g")
      Try {
        // TODO: attempt to setup foreign key delete cascade for the FlatGroupMemberTable membership array?

        samsql"""delete from ${FlatGroupMemberTable as f}
               where ANY(${f.groupMembershipPath}) = ${GroupTable.groupPKQueryForGroup(groupName)}""".update().apply()

        // foreign keys in accessInstructions and groupMember tables are set to cascade delete
        // note: this will not remove this group from any parent groups and will throw a
        // foreign key constraint violation error if group is still a member of any parent groups
        samsql"delete from ${GroupTable as g} where ${g.name} = ${groupName}".update().apply()
      }.recoverWith {
        case fkViolation: PSQLException if fkViolation.getSQLState == PSQLStateExtensions.FOREIGN_KEY_VIOLATION =>
          Failure(new WorkbenchExceptionWithErrorReport(
            ErrorReport(StatusCodes.Conflict, s"group ${groupName.value} cannot be deleted because it is a member of at least 1 other group")))
      }.get
    })
  }

  // list the users who are members of all the groups in `groupIds`
  override def listIntersectionGroupUsers(groupIds: Set[WorkbenchGroupIdentity], samRequestContext: SamRequestContext): IO[Set[WorkbenchUserId]] = {
    runInTransaction("listIntersectionGroupUsers", samRequestContext)({ implicit session =>
      val membershipMap: Map[GroupPK, Set[WorkbenchUserId]] = listMembersByGroupIdentity(groupIds).collect {
        case FlatGroupMemberRecord(_, groupId, Some(memberUserId), _, _) => (groupId, memberUserId)
      }.groupBy(_._1).mapValues { _.map{ case (_, memberUserId) => memberUserId}.toSet }

      membershipMap.values.reduce((a, b) => a intersect b)
    })
  }

  override def listUserDirectMemberships(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Stream[WorkbenchGroupIdentity]] = {
    runInTransaction("listUserDirectMemberships", samRequestContext)({ implicit session =>
      val f = FlatGroupMemberTable.syntax("f")
      val g = GroupTable.syntax("g")
      val p = PolicyTable.syntax("p")
      val r = ResourceTable.syntax("r")
      val rt = ResourceTypeTable.syntax("rt")

      samsql"""select ${g.result.name}, ${p.result.name}, ${r.result.name}, ${rt.result.name}
              from ${GroupTable as g}
              join ${FlatGroupMemberTable as f} on ${f.groupId} = ${g.id}
              left join ${PolicyTable as p} on ${p.groupId} = ${g.id}
              left join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
              left join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
              where ${f.memberUserId} = ${userId} and array_length(${f.groupMembershipPath}, 1) = 1"""
        .map(resultSetToGroupIdentity(_, g, p, r, rt)).list().apply().toStream
    })
  }

  override def listUsersGroups(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]] = {
    listMemberOfGroups(userId, samRequestContext)
  }

  override def listAncestorGroups(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]] = {
    listMemberOfGroups(groupId, samRequestContext)
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
  private def directMembershipGroupClause(groupId: WorkbenchGroupIdentity): SQLSyntax = {
    val f = FlatGroupMemberTable.syntax("f")
    samsqls"${f.groupMembershipPath}[array_upper(${f.groupMembershipPath}, 1)] = ${workbenchGroupIdentityToGroupPK(groupId)}"
  }

  // there is probably some implicit magic which avoids this but I don't know it
  private def convertToFlatGroupMemberTable(f: QuerySQLSyntaxProvider[SQLSyntaxSupport[FlatGroupMemberRecord], FlatGroupMemberRecord])
                                           (rs: WrappedResultSet): FlatGroupMemberRecord = FlatGroupMemberTable.apply(f)(rs)

  // adaptation of PostgresDirectoryDAO.listMemberOfGroups
  private def listMemberOfGroups(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]]  = {
    val f = FlatGroupMemberTable.syntax("f")
    val g = GroupTable.syntax("g")
    val p = PolicyTable.syntax("p")

    val topQueryWhere = subject match {
      case userId: WorkbenchUserId => samsqls"where ${f.memberUserId} = ${userId}"
      case workbenchGroupIdentity: WorkbenchGroupIdentity => samsqls"where ${f.memberGroupId} = (${workbenchGroupIdentityToGroupPK(workbenchGroupIdentity)})"
      case _ => throw new WorkbenchException(s"Unexpected WorkbenchSubject. Expected WorkbenchUserId or WorkbenchGroupIdentity but got ${subject}")
    }

    runInTransaction("listMemberOfGroups", samRequestContext)({ implicit session =>
      val r = ResourceTable.syntax("r")
      val rt = ResourceTypeTable.syntax("rt")

      val listGroupsQuery =
        samsql"""select * from ${FlatGroupMemberTable as f}
            join ${GroupTable as g} on ${f.groupId} = ${g.id}
            left join ${PolicyTable as p} on ${p.groupId} = ${g.id}
            left join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
            left join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}"""

      listGroupsQuery.map(resultSetToGroupIdentity(_, g, p, r, rt)).list().apply().toSet
    })
  }

  // get all of the groups that group `member` is a member of - directly or transitively
  private def listMyGroupRecords(member: GroupPK)(implicit session: DBSession) = {
    val gm = FlatGroupMemberTable.column
    val f = FlatGroupMemberTable.syntax("f")
    val query = samsql"select * from ${FlatGroupMemberTable as f} where ${gm.memberGroupId} = ${member}"
    query.map(convertToFlatGroupMemberTable(f)).list.apply()
  }

  // get all of the direct or transitive members of all `groups`
  private def listMembersByPKs(groups: Traversable[GroupPK])(implicit session: DBSession) = {
    val gm = FlatGroupMemberTable.column
    val f = FlatGroupMemberTable.syntax("f")
    val query = samsql"select * from ${FlatGroupMemberTable as f} where ${gm.groupId} IN (${groups})"
    query.map(convertToFlatGroupMemberTable(f)).list.apply()
  }

  // get all of the direct or transitive members of all `groups`
  private def listMembersByGroupIdentity(groups: Traversable[WorkbenchGroupIdentity])(implicit session: DBSession) = {
    val gm = FlatGroupMemberTable.column
    val f = FlatGroupMemberTable.syntax("f")
    val query = samsql"select * from ${FlatGroupMemberTable as f} where ${gm.groupId} IN (${groups map workbenchGroupIdentityToGroupPK})"
    query.map(convertToFlatGroupMemberTable(f)).list.apply()
  }
}
