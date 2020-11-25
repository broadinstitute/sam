package org.broadinstitute.dsde.workbench.sam.directory

import akka.http.scaladsl.model.StatusCodes
import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchException, WorkbenchExceptionWithErrorReport, WorkbenchGroupIdentity, WorkbenchGroupName, WorkbenchSubject, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory.SqlInterpolationWithSamBinders
import org.broadinstitute.dsde.workbench.sam.db._
import org.broadinstitute.dsde.workbench.sam.db.tables.{FlatGroupMemberRecord, FlatGroupMemberTable, GroupPK, GroupRecord, GroupTable, PolicyTable, ResourceTable, ResourceTypeTable}
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicyName, BasicWorkbenchGroup, FullyQualifiedPolicyId, FullyQualifiedResourceId, ResourceId, ResourceTypeName}
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
                WHERE ${memberClause(member)} AND ${f.groupId} = (${workbenchGroupIdentityToGroupPK(groupId)})"""
      query.map(rs => rs.int(1)).single().apply().getOrElse(0) > 0
    })
  }

  override def addGroupMember(groupId: WorkbenchGroupIdentity, addMember: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] = {
    runInTransaction("addGroupMember", samRequestContext)({ implicit session =>
      insertGroupMembers(queryForGroupPKs(Set(groupId)).head, Set(addMember))
    }).map(count => count > 0)
  }

  override def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] = {
    val f = FlatGroupMemberTable.syntax("f")

    // remove all records where `groupId` is the direct parent of `removeMember`
    runInTransaction("removeGroupMember", samRequestContext)({ implicit session =>
      val query =
        samsql"""DELETE FROM ${FlatGroupMemberTable as f}
                     WHERE ${memberClause(removeMember)} AND ${directMembershipClause(groupId)}"""
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
                where (${GroupTable.groupPKQueryForGroup(groupName)}) = ANY(${f.groupMembershipPath}::int[])""".update().apply()

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
      val membershipMap: Map[GroupPK, Set[WorkbenchUserId]] = groupIds.flatMap { groupId =>
        listMembersByGroupIdentity(groupId).collect {
          case FlatGroupMemberRecord(_, groupId, Some(memberUserId), _, _) => (groupId, memberUserId)
        }
      }.groupBy(_._1).mapValues { _.map{ case (_, memberUserId) => memberUserId}.toSet }

      if (membershipMap.isEmpty) Set.empty
      else membershipMap.values.reduce((a, b) => a intersect b)
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

  // NOTE assumption from reading the original queries: loadGroup/loadGroups want *direct* membership only
  override def loadGroup(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[BasicWorkbenchGroup]] = {
    for {
      results <- runInTransaction("loadGroup", samRequestContext)({ implicit session =>
        val g = GroupTable.syntax("g")
        val sg = GroupTable.syntax("sg")
        val f = FlatGroupMemberTable.syntax("f")
        val p = PolicyTable.syntax("p")
        val r = ResourceTable.syntax("r")
        val rt = ResourceTypeTable.syntax("rt")

        import SamTypeBinders._

        samsql"""select ${g.result.email}, ${f.result.memberUserId}, ${sg.result.name}, ${p.result.name}, ${r.result.name}, ${rt.result.name}
                  from ${GroupTable as g}
                  left join ${FlatGroupMemberTable as f} on ${g.id} = ${f.groupId} and ${directMembershipClause(g)}
                  left join ${GroupTable as sg} on ${f.memberGroupId} = ${sg.id}
                  left join ${PolicyTable as p} on ${p.groupId} = ${sg.id}
                  left join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
                  left join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
                  where ${g.name} = ${groupName}"""
          .map { rs =>
            (rs.get[WorkbenchEmail](g.resultName.email),
              rs.stringOpt(f.resultName.memberUserId).map(WorkbenchUserId),
              rs.stringOpt(sg.resultName.name).map(WorkbenchGroupName),
              rs.stringOpt(p.resultName.name).map(AccessPolicyName(_)),
              rs.stringOpt(r.resultName.name).map(ResourceId(_)),
              rs.stringOpt(rt.resultName.name).map(ResourceTypeName(_)))
          }.list().apply()
      })
    } yield {
      if (results.isEmpty) {
        None
      } else {
        val email = results.head._1
        val members: Set[WorkbenchSubject] = results.collect {
          case (_, Some(userId), None, None, None, None) => userId
          case (_, None, Some(subGroupName), None, None, None) => subGroupName
          case (_, None, Some(_), Some(policyName), Some(resourceName), Some(resourceTypeName)) => FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceTypeName, resourceName), policyName)
        }.toSet

        Option(BasicWorkbenchGroup(groupName, members, email))
      }
    }
  }

  override def loadGroups(groupNames: Set[WorkbenchGroupName], samRequestContext: SamRequestContext): IO[Stream[BasicWorkbenchGroup]] = {
    if (groupNames.isEmpty) {
      IO.pure(Stream.empty)
    } else {
      for {
        results <- runInTransaction("loadGroups", samRequestContext)({ implicit session =>
          val g = GroupTable.syntax("g")
          val sg = GroupTable.syntax("sg")
          val f = FlatGroupMemberTable.syntax("f")
          val p = PolicyTable.syntax("p")
          val r = ResourceTable.syntax("r")
          val rt = ResourceTypeTable.syntax("rt")

          import SamTypeBinders._
          samsql"""select ${g.result.name}, ${g.result.email}, ${f.result.memberUserId}, ${sg.result.name}, ${p.result.name}, ${r.result.name}, ${rt.result.name}
                    from ${GroupTable as g}
                    left join ${FlatGroupMemberTable as f} on ${g.id} = ${f.groupId} and ${directMembershipClause(g)}
                    left join ${GroupTable as sg} on ${f.memberGroupId} = ${sg.id}
                    left join ${PolicyTable as p} on ${p.groupId} = ${sg.id}
                    left join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
                    left join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
                    where ${g.name} in (${groupNames})"""
            .map { rs =>
              (rs.get[WorkbenchGroupName](g.resultName.name),
                rs.get[WorkbenchEmail](g.resultName.email),
                rs.stringOpt(f.resultName.memberUserId).map(WorkbenchUserId),
                rs.stringOpt(sg.resultName.name).map(WorkbenchGroupName),
                rs.stringOpt(p.resultName.name).map(AccessPolicyName(_)),
                rs.stringOpt(r.resultName.name).map(ResourceId(_)),
                rs.stringOpt(rt.resultName.name).map(ResourceTypeName(_)))
            }.list().apply()
        })
      } yield {
        results.groupBy(result => (result._1, result._2)).map { case ((groupName, email), results) =>
          val members: Set[WorkbenchSubject] = results.collect {
            case (_, _, Some(userId), None, None, None, None) => userId
            case (_, _, None, Some(subGroupName), None, None, None) => subGroupName
            case (_, _, None, Some(_), Some(policyName), Some(resourceName), Some(resourceTypeName)) => FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceTypeName, resourceName), policyName)
          }.toSet

          BasicWorkbenchGroup(groupName, members, email)
        }
      }.toStream
    }
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
  private def directMembershipClause(g: QuerySQLSyntaxProvider[SQLSyntaxSupport[GroupRecord], GroupRecord]): SQLSyntax = {
    val f = FlatGroupMemberTable.syntax("f")
    samsqls"${f.groupMembershipPath}[array_upper(${f.groupMembershipPath}, 1)] = ${g.id}"
  }

  // selection clause for direct membership:
  // choose when the final `groupMembershipPath` array element is equal to groupId
  private def directMembershipClause(groupId: WorkbenchGroupIdentity): SQLSyntax = {
    val f = FlatGroupMemberTable.syntax("f")
    samsqls"${f.groupMembershipPath}[array_upper(${f.groupMembershipPath}, 1)] = (${workbenchGroupIdentityToGroupPK(groupId)})"
  }

  // adaptation of PostgresDirectoryDAO.listMemberOfGroups
  private def listMemberOfGroups(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]]  = {
    val f = FlatGroupMemberTable.syntax("f")
    val g = GroupTable.syntax("g")
    val p = PolicyTable.syntax("p")

    val where = subject match {
      case userId: WorkbenchUserId => samsqls"where ${f.memberUserId} = ${userId}"
      case workbenchGroupIdentity: WorkbenchGroupIdentity => samsqls"where ${f.memberGroupId} = (${workbenchGroupIdentityToGroupPK(workbenchGroupIdentity)})"
      case _ => throw new WorkbenchException(s"Unexpected WorkbenchSubject. Expected WorkbenchUserId or WorkbenchGroupIdentity but got ${subject}")
    }

    runInTransaction("listMemberOfGroups", samRequestContext)({ implicit session =>
      val r = ResourceTable.syntax("r")
      val rt = ResourceTypeTable.syntax("rt")

      val listGroupsQuery =
        samsql"""select ${g.result.name}, ${p.result.name}, ${r.result.name}, ${rt.result.name}
                 from ${FlatGroupMemberTable as f}
            join ${GroupTable as g} on ${f.groupId} = ${g.id}
            left join ${PolicyTable as p} on ${p.groupId} = ${g.id}
            left join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
            left join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
            ${where}"""

      listGroupsQuery.map(resultSetToGroupIdentity(_, g, p, r, rt)).list().apply().toSet
    })
  }

  // get all of the direct or transitive members of all `groups`

  private def listMembersByGroupIdentity(group: WorkbenchGroupIdentity)(implicit session: DBSession) = {
    val gm = FlatGroupMemberTable.column
    val f = FlatGroupMemberTable.syntax("f")
    val query = samsql"select ${f.resultAll} from ${FlatGroupMemberTable as f} where ${gm.groupId} IN (${workbenchGroupIdentityToGroupPK(group)})"
    query.map(convertToFlatGroupMemberTable(f)).list.apply()
  }

  // there is probably some implicit magic which avoids this but I don't know it
  private def convertToFlatGroupMemberTable(f: QuerySQLSyntaxProvider[SQLSyntaxSupport[FlatGroupMemberRecord], FlatGroupMemberRecord])
                                           (rs: WrappedResultSet): FlatGroupMemberRecord = FlatGroupMemberTable.apply(f)(rs)

}
