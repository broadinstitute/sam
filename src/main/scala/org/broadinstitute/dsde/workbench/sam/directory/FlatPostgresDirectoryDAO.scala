package org.broadinstitute.dsde.workbench.sam.directory

import akka.http.scaladsl.model.StatusCodes
import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchException, WorkbenchExceptionWithErrorReport, WorkbenchGroupIdentity, WorkbenchGroupName, WorkbenchSubject, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory.SqlInterpolationWithSamBinders
import org.broadinstitute.dsde.workbench.sam.db._
import org.broadinstitute.dsde.workbench.sam.db.tables.{FlatGroupMemberRecord, FlatGroupMemberTable, FlatGroupMembershipPath, GroupPK, GroupTable}
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
      val query = samsql"""SELECT count(*) FROM ${FlatGroupMemberTable as f}
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
      val ancestorMemberships = ancestorPaths(groupId)

      // groups and users which have `groupMembers` as ancestors
      val descendantMemberships = descendentPaths(groupMembers)

      val transitiveMembers = ancestorMemberships flatMap { ancestorMembership =>
        val (ancestor, ancestorPath) = ancestorMembership
        val ancestorsPlusGroup = ancestorPath.append(groupId)

        val ancestorUsers = userMembers.map { userId =>
          samsqls"(${ancestor}, ${userId}, ${None}, ${ancestorsPlusGroup})"
        }.toList

        val ancestorGroups = groupMembers.map { groupPK =>
          samsqls"(${ancestor}, ${None}, ${groupPK}, ${ancestorsPlusGroup})"
        }

        val descendants = descendantMemberships.map { case (descendant, descendantPath) =>
          val fullPath = ancestorsPlusGroup.append(descendantPath)
          descendant match {
            case Left(userId) => samsqls"(${ancestor}, ${userId}, ${None}, ${fullPath})"
            case Right(groupPk) => samsqls"(${ancestor}, ${None}, ${groupPk}, ${fullPath})"
          }
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
    // = the final element of the membership path, retrieved by `path[array_upper(path, 1)]`
    val groupClause = samsqls"${f.groupMembershipPath}[array_upper(f.groupMembershipPath, 1)] = ${workbenchGroupIdentityToGroupPK(groupId)}"

    runInTransaction("removeGroupMember", samRequestContext)({ implicit session =>
      val query = samsql"DELETE FROM ${FlatGroupMemberTable as f} WHERE ${memberClause(removeMember)} AND ${groupClause}"
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

  private def memberClause(member: WorkbenchSubject): SQLSyntax = {
    val f = FlatGroupMemberTable.syntax("f")
    member match {
      case subGroupId: WorkbenchGroupIdentity => samsqls"${f.memberGroupId} = (${workbenchGroupIdentityToGroupPK(subGroupId)})"
      case WorkbenchUserId(userId) => samsqls"${f.memberUserId} = $userId"
      case _ => throw new WorkbenchException(s"illegal member $member")
    }
  }

  // there is probably some implicit magic which avoids this but I don't know it
  private def convertToFlatGroupMemberTable(f: QuerySQLSyntaxProvider[SQLSyntaxSupport[FlatGroupMemberRecord], FlatGroupMemberRecord])
                                           (rs: WrappedResultSet): FlatGroupMemberRecord = FlatGroupMemberTable.apply(f)(rs)

  // get all of the groups that group `member` is a member of - directly or transitively
  private def listMyGroupRecords(member: GroupPK)(implicit session: DBSession) = {
    val gm = FlatGroupMemberTable.column
    val f = FlatGroupMemberTable.syntax("f")

    samsql"""select ${gm.groupId}, ${gm.memberUserId}, ${gm.memberGroupId}, ${gm.groupMembershipPath}
                      from ${FlatGroupMemberTable as f}
                      where ${gm.memberGroupId} = ${member}""".map(convertToFlatGroupMemberTable(f)).list.apply()
  }

  // fetch all records where DB.member_id = this.groupId
  private def ancestorPaths(groupId: GroupPK)(implicit session: DBSession): List[(GroupPK, FlatGroupMembershipPath)] =
    listMyGroupRecords(groupId).map { record => (record.groupId, record.groupMembershipPath) }

  // get all of the direct or transitive members of all `groups`
  private def listMembers(groups: TraversableOnce[GroupPK])(implicit session: DBSession) = {
    val gm = FlatGroupMemberTable.column
    val f = FlatGroupMemberTable.syntax("f")

    samsql"""select ${gm.groupId}, ${gm.memberUserId}, ${gm.memberGroupId}, ${gm.groupMembershipPath}
                      from ${FlatGroupMemberTable as f}
                      where ${gm.groupId} IN (${groups})""".map(convertToFlatGroupMemberTable(f)).list.apply()
  }

  // fetch all records where DB.group_id IN groupMembers
  private def descendentPaths(groups: TraversableOnce[GroupPK])(implicit session: DBSession): List[(Either[WorkbenchUserId, GroupPK], FlatGroupMembershipPath)] = {
    listMembers(groups).map { record =>
      val descendant = (record.memberUserId, record.memberGroupId) match {
        case (Some(userId), None) => Left(userId)
        case (None, Some(groupId)) => Right(groupId)
        case _ => throw new WorkbenchException(s"DB error: ${FlatGroupMemberTable.tableName} record ${record.id} does not contain exactly 1 of: userId, groupId")
      }
      (descendant, record.groupMembershipPath)
    }
  }
}
