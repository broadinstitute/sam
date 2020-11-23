package org.broadinstitute.dsde.workbench.sam.directory

import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.model.{WorkbenchSubject, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory.SqlInterpolationWithSamBinders
import org.broadinstitute.dsde.workbench.sam.db._
import org.broadinstitute.dsde.workbench.sam.db.tables.{FlatGroupMemberTable, FlatGroupMembershipPath, GroupPK}
import scalikejdbc.{DBSession, SQLSyntax}

import scala.concurrent.ExecutionContext

/**
  * Replace the "classic" hierarchical/recursive model with a flattened DB structure optimized for quick reads.
  *
  * For the design, see Appendix A in https://docs.google.com/document/d/1pXAhic_GxM-G9qBFTk0JiTF9YY4nUJfdUEWvbkd_rNw
  */
class FlatPostgresDirectoryDAO (override val dbRef: DbReference, override val ecForDatabaseIO: ExecutionContext)
                               (implicit override val cs: ContextShift[IO]) extends PostgresDirectoryDAO(dbRef, ecForDatabaseIO) {

  override def insertGroupMembers(groupId: GroupPK, members: Set[WorkbenchSubject])(implicit session: DBSession): Int = {
    if (members.isEmpty) {
      0
    } else {
      val userMembers: Set[WorkbenchUserId] = members.collect { case userId: WorkbenchUserId => userId }
      val groupMembers = queryForGroupPKs(members);

      // direct (non-transitive) membership in groupId
      val directMembershipPath = List(groupId)

      val directMemberUsers: List[SQLSyntax] = userMembers.map { userId =>
        samsqls"(${groupId}, ${userId}, ${None}, '{${directMembershipPath}}')"
      }.toList

      val directMemberGroups: List[SQLSyntax] = groupMembers.map { groupPK =>
        samsqls"(${groupId}, ${None}, ${groupPK}, '{${directMembershipPath}}')"
      }

      // groups which have groupId as a direct or transitive subgroup
      val ancestorMemberships: List[(GroupPK, FlatGroupMembershipPath)] = ???  // fetch all records where DB.member_id = this.groupId

      // groups and users which have `groupMembers` as ancestors
      val descendantMemberships: List[(Either[WorkbenchUserId, GroupPK], FlatGroupMembershipPath)] = ???  // fetch all records where DB.group_id IN groupMembers

      val transitiveMembers: List[SQLSyntax] = ancestorMemberships flatMap { ancestorMembership =>
        val (ancestor, ancestorPath) = ancestorMembership
        val ancestorsPlusGroup = ancestorPath.append(groupId)

        val ancestorUsers = userMembers.map { userId =>
          samsqls"(${ancestor}, ${userId}, ${None}, '{${ancestorsPlusGroup}}')"
        }.toList

        val ancestorGroups = groupMembers.map { groupPK =>
          samsqls"(${ancestor}, ${None}, ${groupPK}, '{${ancestorsPlusGroup}}')"
        }

        val descendants: List[SQLSyntax] = descendantMemberships.map { case (descendant, descendantPath) =>
          val fullPath = ancestorsPlusGroup.append(descendantPath)
          descendant match {
            case Left(userId) => samsqls"(${ancestor}, ${userId}, ${None}, '{${fullPath}}')"
            case Right(groupPk) => samsqls"(${ancestor}, ${None}, ${groupPk}, '{${fullPath}}')"
          }
        }

        ancestorUsers ++ ancestorGroups ++ descendants
      }

      val gm = FlatGroupMemberTable.column
      samsql"""insert into ${FlatGroupMemberTable.table} (${gm.groupId}, ${gm.memberUserId}, ${gm.memberGroupId}, ${gm.groupMembershipPath})
        values ${directMemberUsers ++ directMemberGroups ++ transitiveMembers}""".update().apply()
    }
  }
}
