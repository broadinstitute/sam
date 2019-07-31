package org.broadinstitute.dsde.workbench.sam.directory

import java.time.Instant
import java.util.Date

import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{ServiceAccount, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.db._
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.DatabaseSupport
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import scalikejdbc._
import SamParameterBinderFactory._
import akka.http.scaladsl.model.StatusCodes

import scala.concurrent.ExecutionContext

class PostgresDirectoryDAO(protected val dbRef: DbReference,
                           protected val ecForDatabaseIO: ExecutionContext)(implicit executionContext: ExecutionContext) extends DirectoryDAO with DatabaseSupport {
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  override def createGroup(group: BasicWorkbenchGroup, accessInstructionsOpt: Option[String]): IO[BasicWorkbenchGroup] = {
    runInTransaction { implicit session =>
      val groupId: GroupPK = insertGroup(group)

      accessInstructionsOpt.map { accessInstructions =>
        insertAccessInstructions(groupId, accessInstructions)
      }

      insertGroupMembers(groupId, group.members)

      group
    }
  }

  private def insertGroup(group: BasicWorkbenchGroup)(implicit session: DBSession): GroupPK = {
    val groupTableColumn = GroupTable.column
    val insertGroupQuery = samsql"""insert into ${GroupTable.table} (${groupTableColumn.name}, ${groupTableColumn.email}, ${groupTableColumn.updatedDate}, ${groupTableColumn.synchronizedDate})
           values (${group.id}, ${group.email}, ${Option(Instant.now())}, ${None})"""

    GroupPK(insertGroupQuery.updateAndReturnGeneratedKey().apply())
  }

  private def insertAccessInstructions(groupId: GroupPK, accessInstructions: String)(implicit session: DBSession): Int = {
    val accessInstructionsColumn = AccessInstructionsTable.column
    val insertAccessInstructionsQuery = samsql"insert into ${AccessInstructionsTable.table} (${accessInstructionsColumn.groupId}, ${accessInstructionsColumn.instructions}) values (${groupId}, ${accessInstructions})"

    insertAccessInstructionsQuery.update().apply()
  }

  private def insertGroupMembers(groupId: GroupPK, members: Set[WorkbenchSubject])(implicit session: DBSession): Int = {
    if (members.isEmpty) {
      0
    } else {
      // multipleValues (used below to insert all the records into the GroupMemberTable) doesn't really handle implicit parameter binder factories
      // like namedValues does because it takes a Seq[Any] and so it doesn't know to convert our case classes to ParameterBinders using the PBFs.
      // Declaring the PBFs and then using them explicitly to convert our case classes to ParameterBinders enables scalike to properly bind our values
      // to the PreparedStatement. Without these PBFs, scalike throws an error because it doesn't know what SQL type one of our case classes corresponds to
      val groupIdPBF = databaseKeyPbf[GroupPK]
      val userIdPBF = valueObjectPbf[WorkbenchUserId]
      val optionalUserPBF = ParameterBinderFactory.optionalParameterBinderFactory(userIdPBF)
      val optionalGroupPBF = ParameterBinderFactory.optionalParameterBinderFactory(groupIdPBF)

      val parentGroupParameterBinder = groupIdPBF(groupId)

      val memberUserParameterBinders: Seq[(ParameterBinder, ParameterBinder, ParameterBinder)] = members.collect {
        case userId@WorkbenchUserId(_) => (parentGroupParameterBinder, optionalUserPBF(Option(userId)), optionalGroupPBF(None))
      }.toSeq

      val memberGroupNames = members.collect {
        case WorkbenchGroupName(name) => name
      }

      val groupTable = GroupTable.syntax("g")
      val memberGroupRecords: List[GroupRecord]  = withSQL {
        select
          .from(GroupTable as groupTable)
          .where
          .in(groupTable.name, memberGroupNames.toSeq)
      }.map(GroupTable(groupTable)).list().apply()

      val loadedGroupNames = memberGroupRecords.map(_.name.value).toSet
      val missingNames = memberGroupNames -- loadedGroupNames

      if (missingNames.nonEmpty) {
        throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"Some group members not found: [${missingNames.mkString(", ")}]"))
      } else {
        val memberGroupParameterBinders: List[(ParameterBinder, ParameterBinder, ParameterBinder)] = memberGroupRecords.map { groupRecord =>
          (parentGroupParameterBinder, optionalUserPBF(None), optionalGroupPBF(Option(groupRecord.id)))
        }
        val allMemberParameterBinders = (memberUserParameterBinders ++ memberGroupParameterBinders).map(_.productIterator.toSeq)

        val groupMemberColumn = GroupMemberTable.column
        withSQL {
          insert
            .into(GroupMemberTable)
            .columns(groupMemberColumn.groupId, groupMemberColumn.memberUserId, groupMemberColumn.memberGroupId)
            .multipleValues(allMemberParameterBinders: _*)
        }.update().apply()
      }
    }
  }

  override def loadGroup(groupName: WorkbenchGroupName): IO[Option[BasicWorkbenchGroup]] = {
    for {
      results <- runInTransaction { implicit session =>
        val groupTable = GroupTable.syntax("g")
        val subGroupTable = GroupTable.syntax("sG")
        val groupMemberTable = GroupMemberTable.syntax("gM")

        withSQL {
          select(groupTable.email, groupMemberTable.memberUserId, subGroupTable.name)
            .from(GroupTable as groupTable)
            .leftJoin(GroupMemberTable as groupMemberTable)
            .on(groupTable.id, groupMemberTable.groupId)
            .leftJoin(GroupTable as subGroupTable)
            .on(groupMemberTable.memberGroupId, subGroupTable.id)
            .where
            .eq(groupTable.name, groupName)
        }.map(rs => (WorkbenchEmail(rs.string(1)), rs.stringOpt(2).map(WorkbenchUserId), rs.stringOpt(3).map(WorkbenchGroupName)))
          .list().apply()
      }
    } yield {
      if (results.isEmpty) {
        None
      } else {
        val email = results.head._1
        val members: Set[WorkbenchSubject] = results.collect {
          case (_, Some(userId), None) => userId
          case (_, None, Some(subGroupName)) => subGroupName
        }.toSet

        Option(BasicWorkbenchGroup(groupName, members, email))
      }
    }
  }

  override def loadGroups(groupNames: Set[WorkbenchGroupName]): IO[Stream[BasicWorkbenchGroup]] = {
    for {
      results <- runInTransaction { implicit session =>
        val groupTable = GroupTable.syntax("g")
        val subGroupTable = GroupTable.syntax("sG")
        val groupMemberTable = GroupMemberTable.syntax("gM")

        val groupNamesValuesSeq = groupNames.map(_.value).toSeq
        withSQL {
          select(groupTable.name, groupTable.email, groupMemberTable.memberUserId, subGroupTable.name)
            .from(GroupTable as groupTable)
            .leftJoin(GroupMemberTable as groupMemberTable)
            .on(groupTable.id, groupMemberTable.groupId)
            .leftJoin(GroupTable as subGroupTable)
            .on(groupMemberTable.memberGroupId, subGroupTable.id)
            .where
            .in(groupTable.name, groupNamesValuesSeq)
        }.map { rs =>
          (WorkbenchGroupName(rs.string(1)),
            WorkbenchEmail(rs.string(2)),
            rs.stringOpt(3).map(WorkbenchUserId),
            rs.stringOpt(4).map(WorkbenchGroupName))
        }.list().apply()
      }
    } yield {
      results.groupBy(result => (result._1, result._2)).map { case ((groupName, email), results) =>
        val members: Set[WorkbenchSubject] = results.collect {
            case (_, _, Some(userId), None) => userId
            case (_, _, None, Some(subGroupName)) => subGroupName
        }.toSet

        BasicWorkbenchGroup(groupName, members, email)
      }
    }.toStream
  }

  override def loadGroupEmail(groupName: WorkbenchGroupName): IO[Option[WorkbenchEmail]] = {
    batchLoadGroupEmail(Set(groupName)).map(_.toMap.get(groupName))
  }

  override def batchLoadGroupEmail(groupNames: Set[WorkbenchGroupName]): IO[Stream[(WorkbenchGroupName, WorkbenchEmail)]] = {
    runInTransaction { implicit session =>
      val groupTable = GroupTable.syntax("g")

      import SamTypeBinders._
      withSQL {
        select(groupTable.name, groupTable.email)
          .from(GroupTable as groupTable)
          .where
          .in(groupTable.name, groupNames.map(_.value).toSeq)
      }.map(rs => (rs.get[WorkbenchGroupName]("name"), rs.get[WorkbenchEmail]("email"))).list().apply().toStream
    }
  }

  override def deleteGroup(groupName: WorkbenchGroupName): IO[Unit] = {
    runInTransaction { implicit session =>
      val groupTable = GroupTable.syntax("g")

      // foreign keys in accessInstructions and groupMember tables are set to cascade delete
      // note: this will not remove this group from any parent groups and will throw a
      // foreign key constraint violation error if group is still a member of any parent groups
      withSQL {
        delete
          .from(GroupTable as groupTable)
          .where
          .eq(groupTable.name, groupName)
      }.update().apply()
    }
  }

  /**
    * @return true if the subject was added, false if it was already there
    */
  override def addGroupMember(groupId: WorkbenchGroupIdentity, addMember: WorkbenchSubject): IO[Boolean] = {
    runInTransaction { implicit session =>
      val groupPKQuery = workbenchGroupIdentityToGroupPK(groupId)
      val groupMemberColumn = GroupMemberTable.column

      val addMemberQuery = addMember match {
        case memberUser: WorkbenchUserId =>
          samsql"insert into ${GroupMemberTable.table} (${groupMemberColumn.groupId}, ${groupMemberColumn.memberUserId}) values (($groupPKQuery), ${memberUser})"
        case memberGroup: WorkbenchGroupIdentity =>
          val memberGroupPKQuery = workbenchGroupIdentityToGroupPK(memberGroup)
          samsql"insert into ${GroupMemberTable.table} (${groupMemberColumn.groupId}, ${groupMemberColumn.memberGroupId}) values ((${groupPKQuery}), (${memberGroupPKQuery}))"
        case pet: PetServiceAccountId => throw new WorkbenchException(s"pet service accounts cannot be added to groups $pet")
      }
      val added = addMemberQuery.update().apply() > 0

      if (added) {
        updateGroupUpdatedDate(groupId)
      }

      added
    }
  }

  private def updateGroupUpdatedDate(groupId: WorkbenchGroupIdentity)(implicit session: DBSession): Int = {
    val g = GroupTable.column
    samsql"update ${GroupTable.table} set ${g.updatedDate} = ${Instant.now()} where ${g.id} = (${workbenchGroupIdentityToGroupPK(groupId)})".update().apply()
  }

  private def workbenchGroupIdentityToGroupPK(groupId: WorkbenchGroupIdentity): SQLSyntax = {
    groupId match {
      case group: WorkbenchGroupName => groupPKQueryForGroup(group)
      case policy: FullyQualifiedPolicyId => groupPKQueryForPolicy(policy)
    }
  }

  private def groupPKQueryForGroup(groupName: WorkbenchGroupName, groupTableAlias: String = "gpk"): SQLSyntax = {
    val gpk = GroupTable.syntax(groupTableAlias)
    samsqls"select ${gpk.id} from ${GroupTable as gpk} where ${gpk.name} = $groupName"
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
    * @return true if the subject was removed, false if it was already gone
    */
  override def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject): IO[Boolean] = {
    runInTransaction { implicit session =>
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

      if (removed) {
        updateGroupUpdatedDate(groupId)
      }

      removed
    }
  }

  override def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject): IO[Boolean] = {
    val gm = GroupMemberTable.syntax("gm")
    val subGroupMemberTable = SubGroupMemberTable("sub_group")
    val sg = subGroupMemberTable.syntax("sg")

    val memberClause: SQLSyntax = member match {
      case subGroupId: WorkbenchGroupIdentity => samsqls"${sg.memberGroupId} = (${workbenchGroupIdentityToGroupPK(subGroupId)})"
      case WorkbenchUserId(userId) => samsqls"${sg.memberUserId} = $userId"
      case _ => throw new WorkbenchException(s"illegal member $member")
    }

    runInTransaction { implicit session =>
      // https://www.postgresql.org/docs/9.6/queries-with.html
      // in the recursive query below, UNION, as opposed to UNION ALL, should break out of cycles because it removes duplicates
      val query = samsql"""WITH RECURSIVE ${recursiveMembersQuery(groupId, subGroupMemberTable)}
        SELECT count(*)
        FROM ${subGroupMemberTable as sg} WHERE $memberClause"""

      query.map(rs => rs.int(1)).single().apply().getOrElse(0) == 1
    }
  }

  override def updateSynchronizedDate(groupId: WorkbenchGroupIdentity): IO[Unit] = {
    runInTransaction { implicit session =>
      val g = GroupTable.column
      samsql"update ${GroupTable.table} set ${g.synchronizedDate} = ${Instant.now()} where ${g.id} = (${workbenchGroupIdentityToGroupPK(groupId)})".update().apply()
    }
  }

  override def getSynchronizedDate(groupId: WorkbenchGroupIdentity): IO[Option[Date]] = ???

  override def getSynchronizedEmail(groupId: WorkbenchGroupIdentity): IO[Option[WorkbenchEmail]] = ???

  override def loadSubjectFromEmail(email: WorkbenchEmail): IO[Option[WorkbenchSubject]] = ???

  override def loadSubjectEmail(subject: WorkbenchSubject): IO[Option[WorkbenchEmail]] = ???

  override def loadSubjectEmails(subjects: Set[WorkbenchSubject]): IO[Stream[WorkbenchEmail]] = ???

  override def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId): IO[Option[WorkbenchSubject]] = ???

  override def createUser(user: WorkbenchUser): IO[WorkbenchUser] = {
    runInTransaction { implicit session =>
      val userColumn = UserTable.column

      val insertUserQuery = samsql"insert into ${UserTable.table} (${userColumn.id}, ${userColumn.email}, ${userColumn.googleSubjectId}, ${userColumn.enabled}) values (${user.id}, ${user.email}, ${user.googleSubjectId}, true)"
      insertUserQuery.update.apply
      user
    }
  }

  override def loadUser(userId: WorkbenchUserId): IO[Option[WorkbenchUser]] = {
    runInTransaction { implicit session =>
      val userTable = UserTable.syntax

      val loadUserQuery = samsql"select ${userTable.resultAll} from ${UserTable as userTable} where ${userTable.id} = ${userId}"
      loadUserQuery.map(UserTable(userTable))
        .single().apply().map(unmarshalUserRecord)
    }
  }

  override def loadUsers(userIds: Set[WorkbenchUserId]): IO[Stream[WorkbenchUser]] = {
    runInTransaction { implicit session =>
      val userTable = UserTable.syntax

      val loadUsersQuery = samsql"select ${userTable.resultAll} from ${UserTable as userTable} where ${userTable.id} in (${userIds})"
      loadUsersQuery.map(UserTable(userTable))
        .list().apply().map(unmarshalUserRecord).toStream
    }
  }

  // Not worrying about cascading deletion of user's pet SAs because LDAP doesn't delete user's pet SAs automatically
  override def deleteUser(userId: WorkbenchUserId): IO[Unit] = {
    runInTransaction { implicit session =>
      val userTable = UserTable.syntax
      samsql"delete from ${UserTable.table} where ${userTable.id} = ${userId}".update().apply()
    }
  }

  override def listUsersGroups(userId: WorkbenchUserId): IO[Set[WorkbenchGroupIdentity]] = {
    listMemberOfGroups(userId)
  }

  /** Extracts a WorkbenchGroupIdentity from a SQL query
    *
    * @param rs of form (groupName, Option[policyName], Option[resourceName], Option[resourceTypeName]
    * @param g SQLSyntaxProvider for GroupTable
    * @param p SQLSyntaxProvider for PolicyTable
    * @param r SQLSyntaxProvider for ResourceTable
    * @param rt SQLSyntaxProvider for ResourceTypeTable
    * @return Either a WorkbenchGroupName or a FullyQualifiedPolicyId if policy information is available
    */
  private def resultSetToGroupIdentity(rs: WrappedResultSet,
                                       g: QuerySQLSyntaxProvider[SQLSyntaxSupport[GroupRecord], GroupRecord],
                                       p: QuerySQLSyntaxProvider[SQLSyntaxSupport[PolicyRecord], PolicyRecord],
                                       r: QuerySQLSyntaxProvider[SQLSyntaxSupport[ResourceRecord], ResourceRecord],
                                       rt: QuerySQLSyntaxProvider[SQLSyntaxSupport[ResourceTypeRecord], ResourceTypeRecord]): WorkbenchGroupIdentity = {
    (rs.stringOpt(p.resultName.name), rs.stringOpt(r.resultName.name), rs.stringOpt(rt.resultName.name)) match {
      case (Some(policyName), Some(resourceId), Some(resourceTypeName)) =>
        FullyQualifiedPolicyId(FullyQualifiedResourceId(ResourceTypeName(resourceTypeName), ResourceId(resourceId)), AccessPolicyName(policyName))
      case (None, None, None) =>
        WorkbenchGroupName(rs.string(g.resultName.name))
      case (policyOpt, resourceOpt, resourceTypeOpt) =>
        throw new WorkbenchException(s"Inconsistent result. Expected either nothing or names for the policy, resource, and resource type, but instead got (policy = ${policyOpt}, resource = ${resourceOpt}, resourceType = ${resourceTypeOpt})")
    }
  }

  override def listUserDirectMemberships(userId: WorkbenchUserId): IO[Stream[WorkbenchGroupIdentity]] = {
    runInTransaction { implicit session =>
      val gm = GroupMemberTable.syntax("gm")
      val g = GroupTable.syntax("g")
      val p = PolicyTable.syntax("p")
      val r = ResourceTable.syntax("r")
      val rt = ResourceTypeTable.syntax("rt")

      samsql"""select ${g.result.name}, ${p.result.name}, ${r.result.name}, ${rt.result.name}
              from ${GroupTable as g}
              join ${GroupMemberTable as gm} on ${gm.groupId} = ${g.id}
              left join ${PolicyTable as p} on ${p.groupId} = ${g.id}
              left join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
              left join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
              where ${gm.memberUserId} = ${userId}"""
        .map(resultSetToGroupIdentity(_, g, p, r, rt)).list().apply().toStream
    }
  }

  /**
    * This query attempts to list the User records that are members of ALL the groups given in the groupIds parameter.
    *
    * The overall approach is to take each group and flatten its membership to determine the full set of all the users
    * that are a member of that group, its children, its children's children, etc. Once we have the flattened group
    * membership for each group, we do a big join statement to select only those users that showed up in the flattened
    * list for each group.
    *
    * The crux of this implementation is the use of PostgreSQL's `WITH` statement, https://www.postgresql.org/docs/9.6/queries-with.html
    * These statements allow us to create Common Table Expressions (CTE), which are basically temporary tables that
    * exist only for the duration of the query.  You can create multiple CTEs using a single `WITH` by comma separating
    * each `SELECT` that creates each individual CTE.  In this implementation, we use `WITH RECURSIVE` to create N CTEs
    * where N is the cardinality of the groupIds parameter.  Each of these CTEs is the flattened group membership for
    * one of the WorkbenchGroupIdentities.
    *
    * The query maintains a table alias for each of the CTEs.  The final part of the query uses this list of aliases to
    * join each of them based on the memberUserId column to give us the final result of only those memberUserIds that
    * showed up as an entry in every CTE.
    * @param groupIds
    * @return Set of WorkbenchUserIds that are members of each group specified by groupIds
    */
  override def listIntersectionGroupUsers(groupIds: Set[WorkbenchGroupIdentity]): IO[Set[WorkbenchUserId]] = {
    // the implementation of this is a little fancy and is able to do the entire intersection in a single request
    // the general structure of the query is:
    // WITH RECURSIVE [subGroupsQuery for each group] SELECT user_id FROM [all the subgroup queries joined on user_id]
    case class QueryAndTable(recursiveMembersQuery: SQLSyntax, table: SubGroupMemberTable)
    val gm = GroupMemberTable.syntax("gm")

    // the toSeq below is important to fix a predictable order
    val recursiveMembersQueries = groupIds.toSeq.zipWithIndex.map { case (groupId, index) =>
      // need each subgroup table to be named uniquely
      // this is careful not to use a user defined string (e.g. the group's name) to avoid sql injection attacks
      val subGroupTable = SubGroupMemberTable("sub_group_" + index)

      QueryAndTable(recursiveMembersQuery(groupId, subGroupTable), subGroupTable)
    }

    val allRecursiveMembersQueries = SQLSyntax.join(recursiveMembersQueries.map(_.recursiveMembersQuery), sqls",", false)

    // need to handle the first table special, all others will join back to this
    val firstTable = recursiveMembersQueries.head.table
    val ft = firstTable.syntax
    val allSubGroupTableJoins = recursiveMembersQueries.tail.map(_.table).foldLeft(samsqls"") { (joins, nextTable) =>
      val nt = nextTable.syntax
      samsqls"$joins join ${nextTable as nt} on ${ft.memberUserId} = ${nt.memberUserId} "
    }

    runInTransaction { implicit session =>
      samsql"""with recursive $allRecursiveMembersQueries
              select ${ft.memberUserId}
              from ${firstTable as ft} $allSubGroupTableJoins where ${ft.memberUserId} is not null""".map(rs => WorkbenchUserId(rs.string(1))).list().apply().toSet
    }
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
  private def recursiveMembersQuery(groupId: WorkbenchGroupIdentity, subGroupMemberTable: SubGroupMemberTable, groupMemberTableAlias: String = "gm"): scalikejdbc.SQLSyntax = {
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

  private def directMembersQuery(groupId: WorkbenchGroupIdentity, groupMemberTableAlias: String = "gm") = {
    val gm = GroupMemberTable.syntax(groupMemberTableAlias)
    samsqls"""select ${gm.groupId}, ${gm.memberGroupId}, ${gm.memberUserId}
               from ${GroupMemberTable as gm}
               where ${gm.groupId} = (${workbenchGroupIdentityToGroupPK(groupId)})"""
  }

  private def listMemberOfGroups(subject: WorkbenchSubject): IO[Set[WorkbenchGroupIdentity]] = {
    val gm = GroupMemberTable.syntax("gm")
    val g = GroupTable.syntax("g")
    val p = PolicyTable.syntax("p")

    val topQueryWhere = subject match {
      case userId: WorkbenchUserId => samsqls"where ${gm.memberUserId} = ${userId}"
      case workbenchGroupIdentity: WorkbenchGroupIdentity => samsqls"where ${gm.memberGroupId} = (${workbenchGroupIdentityToGroupPK(workbenchGroupIdentity)})"
      case _ => throw new WorkbenchException(s"Unexpected WorkbenchSubject. Expected WorkbenchUserId or WorkbenchGroupIdentity but got ${subject}")
    }

    runInTransaction { implicit session =>
      val ancestorGroupsTable = SubGroupMemberTable("ancestor_groups")
      val ag = ancestorGroupsTable.syntax("ag")
      val agColumn = ancestorGroupsTable.column

      val pg = GroupMemberTable.syntax("parent_groups")
      val r = ResourceTable.syntax("r")
      val rt = ResourceTypeTable.syntax("rt")

      val listGroupsQuery =
        samsql"""WITH RECURSIVE ${ancestorGroupsTable.table}(${agColumn.parentGroupId}, ${agColumn.memberGroupId}) AS (
                    select ${gm.groupId}, ${gm.memberGroupId}
                    from ${GroupMemberTable as gm}
                    ${topQueryWhere}
                    union
                    select ${pg.groupId}, ${pg.memberGroupId}
                    from ${GroupMemberTable as pg}
                    join ${ancestorGroupsTable as ag} ON ${agColumn.parentGroupId} = ${pg.memberGroupId}
          ) select distinct(${g.name}) as ${g.resultName.name}, ${p.result.name}, ${r.result.name}, ${rt.result.name}
            from ${GroupTable as g}
            join ${ancestorGroupsTable as ag} on ${ag.parentGroupId} = ${g.id}
            left join ${PolicyTable as p} on ${p.groupId} = ${g.id}
            left join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
            left join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}"""

      listGroupsQuery.map(resultSetToGroupIdentity(_, g, p, r, rt)).list().apply().toSet
    }
  }

  override def listAncestorGroups(groupId: WorkbenchGroupIdentity): IO[Set[WorkbenchGroupIdentity]] = {
    listMemberOfGroups(groupId)
  }

  override def enableIdentity(subject: WorkbenchSubject): IO[Unit] = {
    runInTransaction { implicit session =>
      subject match {
        case userId: WorkbenchUserId =>
          val u = UserTable.column
          samsql"update ${UserTable.table} set ${u.enabled} = true where ${u.id} = ${userId}".update().apply()
        case _ => // other types of WorkbenchSubjects cannot be enabled
      }
    }
  }

  override def disableIdentity(subject: WorkbenchSubject): IO[Unit] = {
    runInTransaction { implicit session =>
      subject match {
        case userId: WorkbenchUserId =>
          val u = UserTable.column
          samsql"update ${UserTable.table} set ${u.enabled} = false where ${u.id} = ${userId}".update().apply()
        case _ => // other types of WorkbenchSubjects cannot be disabled
      }
    }
  }

  override def isEnabled(subject: WorkbenchSubject): IO[Boolean] = {
    runInTransaction { implicit session =>
      val userIdOpt = subject match {
        case user: WorkbenchUserId => Option(user)
        case PetServiceAccountId(user, _) => Option(user)
        case _ => None
      }

      val u = UserTable.column

      userIdOpt.flatMap { userId =>
        samsql"select ${u.enabled} from ${UserTable.table} where ${u.id} = ${userId}"
          .map(rs => rs.boolean(u.enabled)).single().apply()
      }.getOrElse(false)
    }
  }

  override def getUserFromPetServiceAccount(petSA: ServiceAccountSubjectId): IO[Option[WorkbenchUser]] = {
    runInTransaction { implicit session =>
      val petServiceAccountTable = PetServiceAccountTable.syntax
      val userTable = UserTable.syntax

      val loadUserQuery = samsql"""select ${userTable.resultAll}
                from ${UserTable as userTable}
                join ${PetServiceAccountTable as petServiceAccountTable} on ${petServiceAccountTable.userId} = ${userTable.id}
                where ${petServiceAccountTable.googleSubjectId} = ${petSA}"""

      val userRecordOpt: Option[UserRecord] = loadUserQuery.map(UserTable(userTable)).single().apply()
      userRecordOpt.map(unmarshalUserRecord)
    }
  }

  override def createPetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount] = {
    runInTransaction { implicit session =>
      val petServiceAccountColumn = PetServiceAccountTable.column

      samsql"""insert into ${PetServiceAccountTable.table} (${petServiceAccountColumn.userId}, ${petServiceAccountColumn.project}, ${petServiceAccountColumn.googleSubjectId}, ${petServiceAccountColumn.email}, ${petServiceAccountColumn.displayName})
           values (${petServiceAccount.id.userId}, ${petServiceAccount.id.project}, ${petServiceAccount.serviceAccount.subjectId}, ${petServiceAccount.serviceAccount.email}, ${petServiceAccount.serviceAccount.displayName})"""
        .update().apply()
      petServiceAccount
    }
  }

  override def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId): IO[Option[PetServiceAccount]] = {
    runInTransaction { implicit session =>
      val petServiceAccountTable = PetServiceAccountTable.syntax

      val loadPetQuery = samsql"""select ${petServiceAccountTable.resultAll}
       from ${PetServiceAccountTable as petServiceAccountTable}
       where ${petServiceAccountTable.userId} = ${petServiceAccountId.userId} and ${petServiceAccountTable.project} = ${petServiceAccountId.project}"""

      val petRecordOpt = loadPetQuery.map(PetServiceAccountTable(petServiceAccountTable)).single().apply()
      petRecordOpt.map(unmarshalPetServiceAccountRecord)
    }
  }

  override def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId): IO[Unit] = {
    runInTransaction { implicit session =>
      val petServiceAccountTable = PetServiceAccountTable.syntax
      val deletePetQuery = samsql"delete from ${PetServiceAccountTable.table} where ${petServiceAccountTable.userId} = ${petServiceAccountId.userId} and ${petServiceAccountTable.project} = ${petServiceAccountId.project}"
      if (deletePetQuery.update().apply() != 1) {
        throw new WorkbenchException(s"${petServiceAccountId} cannot be deleted because it already does not exist")
      }
    }
  }

  override def getAllPetServiceAccountsForUser(userId: WorkbenchUserId): IO[Seq[PetServiceAccount]] = {
    runInTransaction { implicit session =>
      val petServiceAccountTable = PetServiceAccountTable.syntax

      val loadPetsQuery = samsql"""select ${petServiceAccountTable.resultAll}
                from ${PetServiceAccountTable as petServiceAccountTable} where ${petServiceAccountTable.userId} = ${userId}"""

      val petRecords = loadPetsQuery.map(PetServiceAccountTable(petServiceAccountTable)).list().apply()
      petRecords.map(unmarshalPetServiceAccountRecord)
    }
  }

  override def updatePetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount] = {
    runInTransaction { implicit session =>
      val petServiceAccountColumn = PetServiceAccountTable.column
      val updatePetQuery = samsql"""update ${PetServiceAccountTable.table} set
        ${petServiceAccountColumn.googleSubjectId} = ${petServiceAccount.serviceAccount.subjectId},
        ${petServiceAccountColumn.email} = ${petServiceAccount.serviceAccount.email},
        ${petServiceAccountColumn.displayName} = ${petServiceAccount.serviceAccount.displayName}
        where ${petServiceAccountColumn.userId} = ${petServiceAccount.id.userId} and ${petServiceAccountColumn.project} = ${petServiceAccount.id.project}"""

      if (updatePetQuery.update().apply() != 1) {
        throw new WorkbenchException(s"Update cannot be applied because ${petServiceAccount.id} does not exist")
      }

      petServiceAccount
    }
  }

  private def unmarshalPetServiceAccountRecord(petRecord: PetServiceAccountRecord): PetServiceAccount = {
    PetServiceAccount(PetServiceAccountId(petRecord.userId, petRecord.project), ServiceAccount(petRecord.googleSubjectId, petRecord.email, petRecord.displayName))
  }

  private def unmarshalUserRecord(userRecord: UserRecord): WorkbenchUser = {
    WorkbenchUser(userRecord.id, userRecord.googleSubjectId, userRecord.email)
  }

  override def getManagedGroupAccessInstructions(groupName: WorkbenchGroupName): IO[Option[String]] = {
    runInTransaction { implicit session =>
      val groupTable = GroupTable.syntax
      val accessInstructionsTable = AccessInstructionsTable.syntax

      val loadAccessInstructionsQuery = samsql"""select ${accessInstructionsTable.resultAll}
                from ${AccessInstructionsTable as accessInstructionsTable}
                join ${GroupTable as groupTable} on ${groupTable.id} = ${accessInstructionsTable.groupId}
                where ${groupTable.name} = ${groupName}"""

      val accessInstructionsOpt = loadAccessInstructionsQuery.map(AccessInstructionsTable(accessInstructionsTable)).single().apply()
      accessInstructionsOpt.map(_.instructions)
    }
  }

  override def setManagedGroupAccessInstructions(groupName: WorkbenchGroupName, accessInstructions: String): IO[Unit] = {
    runInTransaction { implicit session =>
      val groupPKQuery = workbenchGroupIdentityToGroupPK(groupName)
      val accessInstructionsColumn = AccessInstructionsTable.column

      val upsertAccessInstructionsQuery = samsql"""insert into ${AccessInstructionsTable.table}
                            (${accessInstructionsColumn.groupId}, ${accessInstructionsColumn.instructions})
                            values((${groupPKQuery}), ${accessInstructions})
                            on conflict (${accessInstructionsColumn.groupId})
                            do update set ${accessInstructionsColumn.instructions} = ${accessInstructions}
                            where ${AccessInstructionsTable.syntax.groupId} = (${groupPKQuery})"""

      upsertAccessInstructionsQuery.update().apply()
    }
  }

  override def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId): IO[Unit] = ???
}

// these 2 case classes represent the logical table used in nested group queries
// this table does not actually exist but looks like a table in a WITH RECURSIVE query
private final case class SubGroupMemberRecord(parentGroupId: GroupPK, memberUserId: Option[WorkbenchUserId], memberGroupId: Option[GroupPK])
private final case class SubGroupMemberTable(override val tableName: String) extends SQLSyntaxSupport[SubGroupMemberRecord] {
  // need to specify column names explicitly because this table does not actually exist in the database
  override val columnNames: Seq[String] = Seq("parent_group_id", "member_user_id", "member_group_id")
}

