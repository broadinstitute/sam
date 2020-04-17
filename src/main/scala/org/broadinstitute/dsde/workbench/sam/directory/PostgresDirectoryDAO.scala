package org.broadinstitute.dsde.workbench.sam.directory

import java.time.Instant
import java.util.Date

import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.db._
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedPolicyId, _}
import org.broadinstitute.dsde.workbench.sam.util.DatabaseSupport
import scalikejdbc._
import SamParameterBinderFactory._
import akka.http.scaladsl.model.StatusCodes
import io.opencensus.trace.Span
import org.broadinstitute.dsde.workbench.sam.db.dao.{PostgresGroupDAO, SubGroupMemberTable}
import org.postgresql.util.PSQLException
import org.broadinstitute.dsde.workbench.sam._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Try}

class PostgresDirectoryDAO(protected val dbRef: DbReference,
                           protected val ecForDatabaseIO: ExecutionContext)(implicit val cs: ContextShift[IO]) extends DirectoryDAO with DatabaseSupport with PostgresGroupDAO {

  override def createGroup(group: BasicWorkbenchGroup, accessInstructionsOpt: Option[String]): IO[BasicWorkbenchGroup] = {
    runInTransaction() { implicit session =>
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

    Try {
      GroupPK(insertGroupQuery.updateAndReturnGeneratedKey().apply())
    }.recoverWith {
      case duplicateException: PSQLException if duplicateException.getSQLState == PSQLStateExtensions.UNIQUE_VIOLATION =>
        Failure(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group name ${group.id.value} already exists")))
    }.get
  }

  private def insertAccessInstructions(groupId: GroupPK, accessInstructions: String)(implicit session: DBSession): Int = {
    val accessInstructionsColumn = AccessInstructionsTable.column
    val insertAccessInstructionsQuery = samsql"insert into ${AccessInstructionsTable.table} (${accessInstructionsColumn.groupId}, ${accessInstructionsColumn.instructions}) values (${groupId}, ${accessInstructions})"

    insertAccessInstructionsQuery.update().apply()
  }

  override def loadGroup(groupName: WorkbenchGroupName): IO[Option[BasicWorkbenchGroup]] = {
    for {
      results <- runInTransaction() { implicit session =>
        val g = GroupTable.syntax("g")
        val sg = GroupTable.syntax("sg")
        val gm = GroupMemberTable.syntax("gm")
        val p = PolicyTable.syntax("p")
        val r = ResourceTable.syntax("r")
        val rt = ResourceTypeTable.syntax("rt")

        import SamTypeBinders._

        samsql"""select ${g.result.email}, ${gm.result.memberUserId}, ${sg.result.name}, ${p.result.name}, ${r.result.name}, ${rt.result.name}
                  from ${GroupTable as g}
                  left join ${GroupMemberTable as gm} on ${g.id} = ${gm.groupId}
                  left join ${GroupTable as sg} on ${gm.memberGroupId} = ${sg.id}
                  left join ${PolicyTable as p} on ${p.groupId} = ${sg.id}
                  left join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
                  left join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
                  where ${g.name} = ${groupName}"""
          .map { rs =>
            (rs.get[WorkbenchEmail](g.resultName.email),
              rs.stringOpt(gm.resultName.memberUserId).map(WorkbenchUserId),
              rs.stringOpt(sg.resultName.name).map(WorkbenchGroupName),
              rs.stringOpt(p.resultName.name).map(AccessPolicyName(_)),
              rs.stringOpt(r.resultName.name).map(ResourceId(_)),
              rs.stringOpt(rt.resultName.name).map(ResourceTypeName(_)))
          }.list().apply()
      }
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

  override def loadGroups(groupNames: Set[WorkbenchGroupName]): IO[Stream[BasicWorkbenchGroup]] = {
    if (groupNames.isEmpty) {
      IO.pure(Stream.empty)
    } else {
      for {
        results <- runInTransaction() { implicit session =>
          val g = GroupTable.syntax("g")
          val sg = GroupTable.syntax("sg")
          val gm = GroupMemberTable.syntax("gm")
          val p = PolicyTable.syntax("p")
          val r = ResourceTable.syntax("r")
          val rt = ResourceTypeTable.syntax("rt")

          import SamTypeBinders._
          samsql"""select ${g.result.name}, ${g.result.email}, ${gm.result.memberUserId}, ${sg.result.name}, ${p.result.name}, ${r.result.name}, ${rt.result.name}
                    from ${GroupTable as g}
                    left join ${GroupMemberTable as gm} on ${g.id} = ${gm.groupId}
                    left join ${GroupTable as sg} on ${gm.memberGroupId} = ${sg.id}
                    left join ${PolicyTable as p} on ${p.groupId} = ${sg.id}
                    left join ${ResourceTable as r} on ${p.resourceId} = ${r.id}
                    left join ${ResourceTypeTable as rt} on ${r.resourceTypeId} = ${rt.id}
                    where ${g.name} in (${groupNames})"""
            .map { rs =>
              (rs.get[WorkbenchGroupName](g.resultName.name),
                rs.get[WorkbenchEmail](g.resultName.email),
                rs.stringOpt(gm.resultName.memberUserId).map(WorkbenchUserId),
                rs.stringOpt(sg.resultName.name).map(WorkbenchGroupName),
                rs.stringOpt(p.resultName.name).map(AccessPolicyName(_)),
                rs.stringOpt(r.resultName.name).map(ResourceId(_)),
                rs.stringOpt(rt.resultName.name).map(ResourceTypeName(_)))
            }.list().apply()
        }
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

  override def loadGroupEmail(groupName: WorkbenchGroupName): IO[Option[WorkbenchEmail]] = {
    batchLoadGroupEmail(Set(groupName)).map(_.toMap.get(groupName))
  }

  override def batchLoadGroupEmail(groupNames: Set[WorkbenchGroupName]): IO[Stream[(WorkbenchGroupName, WorkbenchEmail)]] = {
    if (groupNames.isEmpty) {
      IO.pure(Stream.empty)
    } else {
      runInTransaction() { implicit session =>
        val g = GroupTable.column

        import SamTypeBinders._
        samsql"select ${g.name}, ${g.email} from ${GroupTable.table} where ${g.name} in (${groupNames})"
            .map(rs => (rs.get[WorkbenchGroupName](g.name), rs.get[WorkbenchEmail](g.email)))
            .list().apply().toStream
      }
    }
  }

  override def deleteGroup(groupName: WorkbenchGroupName): IO[Unit] = {
    runInTransaction() { implicit session =>
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
    }
  }

  /**
    * @return true if the subject was added, false if it was already there
    */
  override def addGroupMember(groupId: WorkbenchGroupIdentity, addMember: WorkbenchSubject): IO[Boolean] = {
    runInTransaction() { implicit session =>
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

      val numberAdded = Try {
        addMemberQuery.update().apply()
      }.recover {
        case duplicateException: PSQLException if duplicateException.getSQLState == PSQLStateExtensions.UNIQUE_VIOLATION => 0
      }

      if (numberAdded.get > 0) {
        updateGroupUpdatedDate(groupId)
        true
      } else {
        false
      }
    }
  }

  private def updateGroupUpdatedDate(groupId: WorkbenchGroupIdentity)(implicit session: DBSession): Int = {
    val g = GroupTable.column
    samsql"update ${GroupTable.table} set ${g.updatedDate} = ${Instant.now()} where ${g.id} = (${workbenchGroupIdentityToGroupPK(groupId)})".update().apply()
  }

  /**
    * @return true if the subject was removed, false if it was already gone
    */
  override def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject): IO[Boolean] = {
    runInTransaction() { implicit session =>
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
    val subGroupMemberTable = SubGroupMemberTable("sub_group")
    val sg = subGroupMemberTable.syntax("sg")

    val memberClause: SQLSyntax = member match {
      case subGroupId: WorkbenchGroupIdentity => samsqls"${sg.memberGroupId} = (${workbenchGroupIdentityToGroupPK(subGroupId)})"
      case WorkbenchUserId(userId) => samsqls"${sg.memberUserId} = $userId"
      case _ => throw new WorkbenchException(s"illegal member $member")
    }

    runInTransaction() { implicit session =>
      // https://www.postgresql.org/docs/9.6/queries-with.html
      // in the recursive query below, UNION, as opposed to UNION ALL, should break out of cycles because it removes duplicates
      val query = samsql"""WITH RECURSIVE ${recursiveMembersQuery(groupId, subGroupMemberTable)}
        SELECT count(*)
        FROM ${subGroupMemberTable as sg} WHERE $memberClause"""

      query.map(rs => rs.int(1)).single().apply().getOrElse(0) > 0
    }
  }

  override def updateSynchronizedDate(groupId: WorkbenchGroupIdentity): IO[Unit] = {
    runInTransaction() { implicit session =>
      val g = GroupTable.column
      samsql"update ${GroupTable.table} set ${g.synchronizedDate} = ${Instant.now()} where ${g.id} = (${workbenchGroupIdentityToGroupPK(groupId)})".update().apply()
    }
  }

  override def getSynchronizedDate(groupId: WorkbenchGroupIdentity): IO[Option[Date]] = {
    runInTransaction() { implicit session =>
      val g = GroupTable.column
      samsql"select ${g.synchronizedDate} from ${GroupTable.table} where ${g.id} = (${workbenchGroupIdentityToGroupPK(groupId)})"
        .map(rs => rs.timestampOpt(g.synchronizedDate).map(_.toJavaUtilDate)).single().apply()
        .getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupId not found")))
    }
  }

  override def getSynchronizedEmail(groupId: WorkbenchGroupIdentity): IO[Option[WorkbenchEmail]] = {
    import SamTypeBinders._
    runInTransaction() { implicit session =>
      val g = GroupTable.column

      samsql"select ${g.email} from ${GroupTable.table} where ${g.id} = (${workbenchGroupIdentityToGroupPK(groupId)})"
        .map(rs => rs.get[Option[WorkbenchEmail]](g.email)).single().apply()
        .getOrElse(throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupId not found")))
    }
  }

  /*
    This query is better than it looks. The problem that this gets around is that we are given an email address,
    which can be for a user, a group, a policy, or a pet service account. We have no definitive way of knowing
    what type it belongs to until we query all four of the tables. You can't do a clean union here because the
    data in the four tables is shaped differently. Hence the nulls. The nulls coerce the data being selected from
    the tables to have the correct shape, and thus make it unionable. The advantage that this give us is that we
    only need to do one trip to the database to figure out what type it is.

    The alternative would be to have a "cleaner" looking function which actually just fires off a specialized query
    each of the four tables.
   */

  override def loadSubjectFromEmail(email: WorkbenchEmail): IO[Option[WorkbenchSubject]] = {
    runInTransaction() { implicit session =>
      val u = UserTable.syntax
      val g = GroupTable.syntax
      val pet = PetServiceAccountTable.syntax
      val pol = PolicyTable.syntax
      val srt = ResourceTypeTable.syntax
      val res = ResourceTable.syntax

      val query = samsql"""
              select ${u.id}, ${None}, ${None}, ${None}, ${None}, ${None}, ${None}
                from ${UserTable as u}
                where ${u.email} = ${email}
              union
              select ${None}, ${g.name}, ${None}, ${None}, ${None}, ${None}, ${None}
                from ${GroupTable as g}
                where ${g.email} = ${email}
              union
              select ${None}, ${None}, ${pet.userId}, ${pet.project}, ${None}, ${None}, ${None}
                from ${PetServiceAccountTable as pet}
                where ${pet.email} = ${email}
              union
              select ${None}, ${None}, ${None}, ${None}, ${srt.name}, ${res.name}, ${pol.name}
                from ${PolicyTable as pol}
                join ${GroupTable as g}
                on ${pol.groupId} = ${g.id}
                join ${ResourceTable as res}
                on ${res.id} = ${pol.resourceId}
                join ${ResourceTypeTable as srt}
                on ${res.resourceTypeId} = ${srt.id}
                where ${g.email} = ${email}"""

      val result = query.map(rs =>
        SubjectConglomerate(
          rs.stringOpt(1).map(WorkbenchUserId),
          rs.stringOpt(2).map(WorkbenchGroupName),
          rs.stringOpt(3).map(WorkbenchUserId),
          rs.stringOpt(4).map(GoogleProject),
          rs.stringOpt(5).map(name => ResourceTypeName(name)),
          rs.stringOpt(6).map(id => ResourceId(id)),
          rs.stringOpt(7).map(name => AccessPolicyName(name))
        )).list().apply()

      //The typical cases are the first two. However, if the subject being loaded is a policy, it will return
      //two rows- one for the underlying group, and one for the policy itself. An alternative is to resolve this
      //within the query itself.
      result.map(unmarshalSubjectConglomerate) match {
        case List() => None
        case List(subject) => Some(subject)
        case List(_: WorkbenchGroupName, policySubject: FullyQualifiedPolicyId) => Some(policySubject)
        case List(policySubject: FullyQualifiedPolicyId, _: WorkbenchGroupName) => Some(policySubject) //order in unions isn't guaranteed so support both cases
        case _ => throw new WorkbenchException(s"Database error: email $email refers to too many subjects.")

      }
    }
  }

  def loadPolicyEmail(policyId: FullyQualifiedPolicyId): IO[Option[WorkbenchEmail]] = {
    runInTransaction() { implicit session =>
      val g = GroupTable.syntax
      val pol = PolicyTable.syntax
      val srt = ResourceTypeTable.syntax
      val res = ResourceTable.syntax

      import SamTypeBinders._

      val query = samsql"""
                     select ${g.result.email}
                     from ${PolicyTable as pol}
                     join ${GroupTable as g}
                      on ${pol.groupId} = ${g.id}
                     join ${ResourceTable as res}
                      on ${res.id} = ${pol.resourceId}
                     join ${ResourceTypeTable as srt}
                      on ${res.resourceTypeId} = ${srt.id}
                     where ${srt.name} = ${policyId.resource.resourceTypeName} and
                      ${res.name} = ${policyId.resource.resourceId} and
                      ${pol.name} = ${policyId.accessPolicyName}"""

      query.map(rs => rs.get[WorkbenchEmail](g.resultName.email)).single().apply()
    }
  }

  override def loadSubjectEmail(subject: WorkbenchSubject): IO[Option[WorkbenchEmail]] = {
    subject match {
      case subject: WorkbenchGroupName => loadGroupEmail(subject)
      case subject: PetServiceAccountId => for {
        petSA <- loadPetServiceAccount(subject)
      } yield petSA.map(_.serviceAccount.email)
      case subject: WorkbenchUserId => for {
        user <- loadUser(subject)
      } yield user.map(_.email)
      case subject: FullyQualifiedPolicyId => loadPolicyEmail(subject)
      case _ => throw new WorkbenchException(s"unexpected subject [$subject]")
    }
  }

  // NOTE: This implementation is a copy/paste of the implementation from LdapDirectoryDAO.  The question was raised
  // whether this should also handle Pets and Policies.  At this time, we don't know if it should, but for backwards
  // compatibility with LdapDirectoryDAO, we're going to use the same implementation for now.
  override def loadSubjectEmails(subjects: Set[WorkbenchSubject]): IO[Stream[WorkbenchEmail]] = {
    val userSubjects = subjects collect { case userId: WorkbenchUserId => userId }
    val groupSubjects = subjects collect { case groupName: WorkbenchGroupName => groupName }

    val users = loadUsers(userSubjects)
    val groups = loadGroups(groupSubjects)

    for {
      userEmails <- users.map(_.map(_.email))
      groupEmails <- groups.map(_.map(_.email))
    } yield userEmails ++ groupEmails
  }

  override def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId): IO[Option[WorkbenchSubject]] = {
    runInTransaction() { implicit session =>
      val u = UserTable.syntax
      val pet = PetServiceAccountTable.syntax

      //Only pets and users can have googleSubjectIds so we won't bother checking for the other types of WorkbenchSubjects
      val query = samsql"""
              select ${u.id}, ${None}, ${None} from ${UserTable as u}
                where ${u.googleSubjectId} = ${googleSubjectId}
              union
              select ${None}, ${pet.userId}, ${pet.project} from ${PetServiceAccountTable as pet}
                where ${pet.googleSubjectId} = ${googleSubjectId}"""

      val result = query.map(rs =>
        SubjectConglomerate(
          rs.stringOpt(1).map(WorkbenchUserId),
          None,
          rs.stringOpt(2).map(WorkbenchUserId),
          rs.stringOpt(3).map(GoogleProject),
          None,
          None,
          None)
      ).single().apply()

      result.map(unmarshalSubjectConglomerate)
    }
  }

  override def createUser(user: WorkbenchUser): IO[WorkbenchUser] = {
    runInTransaction() { implicit session =>
      val userColumn = UserTable.column

      val insertUserQuery = samsql"insert into ${UserTable.table} (${userColumn.id}, ${userColumn.email}, ${userColumn.googleSubjectId}, ${userColumn.enabled}, ${userColumn.identityConcentratorId}) values (${user.id}, ${user.email}, ${user.googleSubjectId}, false, ${user.identityConcentratorId})"

      Try {
        insertUserQuery.update.apply
      }.recoverWith {
        case duplicateException: PSQLException if duplicateException.getSQLState == PSQLStateExtensions.UNIQUE_VIOLATION =>
          Failure(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"identity with id ${user.id} already exists")))
      }.get
      user
    }
  }

  override def loadUser(userId: WorkbenchUserId, parentSpan: Span = null): IO[Option[WorkbenchUser]] = {
    runInTransaction("loadUser", parentSpan) { implicit session =>
      val userTable = UserTable.syntax

      val loadUserQuery = samsql"select ${userTable.resultAll} from ${UserTable as userTable} where ${userTable.id} = ${userId}"
      loadUserQuery.map(UserTable(userTable))
        .single().apply().map(UserTable.unmarshalUserRecord)
    }
  }

  override def loadUserByIdentityConcentratorId(userId: IdentityConcentratorId): IO[Option[WorkbenchUser]] = {
    runInTransaction() { implicit session =>
      val userTable = UserTable.syntax

      val loadUserQuery = samsql"select ${userTable.resultAll} from ${UserTable as userTable} where ${userTable.identityConcentratorId} = ${userId}"
      loadUserQuery.map(UserTable(userTable))
        .single().apply().map(UserTable.unmarshalUserRecord)
    }
  }

  override def setUserIdentityConcentratorId(googleSubjectId: GoogleSubjectId, icId: IdentityConcentratorId): IO[Int] = {
    runInTransaction() { implicit session =>
      val u = UserTable.column
      samsql"update ${UserTable.table} set ${u.identityConcentratorId} = $icId where ${u.googleSubjectId} = $googleSubjectId".update().apply()
    }
  }

  override def loadUsers(userIds: Set[WorkbenchUserId]): IO[Stream[WorkbenchUser]] = {
    if(userIds.nonEmpty) {
      runInTransaction() { implicit session =>
        val userTable = UserTable.syntax

        val loadUsersQuery = samsql"select ${userTable.resultAll} from ${UserTable as userTable} where ${userTable.id} in (${userIds})"
        loadUsersQuery.map(UserTable(userTable))
          .list().apply().map(UserTable.unmarshalUserRecord).toStream
      }
    } else IO.pure(Stream.empty)
  }

  // Not worrying about cascading deletion of user's pet SAs because LDAP doesn't delete user's pet SAs automatically
  override def deleteUser(userId: WorkbenchUserId): IO[Unit] = {
    runInTransaction() { implicit session =>
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
    import SamTypeBinders._
    (rs.stringOpt(p.resultName.name), rs.stringOpt(r.resultName.name), rs.stringOpt(rt.resultName.name)) match {
      case (Some(policyName), Some(resourceId), Some(resourceTypeName)) =>
        FullyQualifiedPolicyId(FullyQualifiedResourceId(ResourceTypeName(resourceTypeName), ResourceId(resourceId)), AccessPolicyName(policyName))
      case (None, None, None) =>
        rs.get[WorkbenchGroupName](g.resultName.name)
      case (policyOpt, resourceOpt, resourceTypeOpt) =>
        throw new WorkbenchException(s"Inconsistent result. Expected either nothing or names for the policy, resource, and resource type, but instead got (policy = ${policyOpt}, resource = ${resourceOpt}, resourceType = ${resourceTypeOpt})")
    }
  }

  override def listUserDirectMemberships(userId: WorkbenchUserId): IO[Stream[WorkbenchGroupIdentity]] = {
    runInTransaction() { implicit session =>
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
    * The final part of the query intersects all the tables defined in the CTE to give us the final result of only
    * those memberUserIds that showed up as an entry in every CTE.
    * @param groupIds
    * @return Set of WorkbenchUserIds that are members of each group specified by groupIds
    */
  override def listIntersectionGroupUsers(groupIds: Set[WorkbenchGroupIdentity]): IO[Set[WorkbenchUserId]] = {
    // the implementation of this is a little fancy and is able to do the entire intersection in a single request
    // the general structure of the query is:
    // WITH RECURSIVE [subGroupsQuery for each group] [SELECT user_id FROM subGroupsQuery_1 INTERSECT SELECT user_id FROM subGroupsQuery_2 INTERSECT ...]
    case class QueryAndTable(recursiveMembersQuery: SQLSyntax, table: SubGroupMemberTable)

    // the toSeq below is important to fix a predictable order
    val recursiveMembersQueries = groupIds.toSeq.zipWithIndex.map { case (groupId, index) =>
      // need each subgroup table to be named uniquely
      // this is careful not to use a user defined string (e.g. the group's name) to avoid sql injection attacks
      val subGroupTable = SubGroupMemberTable("sub_group_" + index)

      QueryAndTable(recursiveMembersQuery(groupId, subGroupTable), subGroupTable)
    }

    val allRecursiveMembersQueries = SQLSyntax.join(recursiveMembersQueries.map(_.recursiveMembersQuery), sqls",", false)

    val intersectionQuery = recursiveMembersQueries.map { queryAndTable =>
      val sg = queryAndTable.table.syntax
      samsqls"select ${sg.memberUserId} from ${queryAndTable.table as sg} where ${sg.memberUserId} is not null"
    }.reduce((left, right) => samsqls"$left INTERSECT $right")

    runInTransaction() { implicit session =>
      samsql"""with recursive $allRecursiveMembersQueries $intersectionQuery""".map(rs => WorkbenchUserId(rs.string(1))).list().apply().toSet
    }
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

    runInTransaction() { implicit session =>
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
    subject match {
      case userId: WorkbenchUserId =>
        runInTransaction() { implicit session =>
        val u = UserTable.column
        samsql"update ${UserTable.table} set ${u.enabled} = true where ${u.id} = ${userId}".update().apply()
      }
      case _ => IO.unit // other types of WorkbenchSubjects cannot be enabled
    }
  }

  override def disableIdentity(subject: WorkbenchSubject): IO[Unit] = {
    runInTransaction() { implicit session =>
      subject match {
        case userId: WorkbenchUserId =>
          val u = UserTable.column
          samsql"update ${UserTable.table} set ${u.enabled} = false where ${u.id} = ${userId}".update().apply()
        case _ => // other types of WorkbenchSubjects cannot be disabled
      }
    }
  }

  override def isEnabled(subject: WorkbenchSubject, parentSpan: Span = null): IO[Boolean] = {
    runInTransaction("isEnabled", parentSpan) { implicit session =>
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
    runInTransaction() { implicit session =>
      val petServiceAccountTable = PetServiceAccountTable.syntax
      val userTable = UserTable.syntax

      val loadUserQuery = samsql"""select ${userTable.resultAll}
                from ${UserTable as userTable}
                join ${PetServiceAccountTable as petServiceAccountTable} on ${petServiceAccountTable.userId} = ${userTable.id}
                where ${petServiceAccountTable.googleSubjectId} = ${petSA}"""

      val userRecordOpt: Option[UserRecord] = loadUserQuery.map(UserTable(userTable)).single().apply()
      userRecordOpt.map(UserTable.unmarshalUserRecord)
    }
  }

  override def createPetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount] = {
    runInTransaction() { implicit session =>
      val petServiceAccountColumn = PetServiceAccountTable.column

      samsql"""insert into ${PetServiceAccountTable.table} (${petServiceAccountColumn.userId}, ${petServiceAccountColumn.project}, ${petServiceAccountColumn.googleSubjectId}, ${petServiceAccountColumn.email}, ${petServiceAccountColumn.displayName})
           values (${petServiceAccount.id.userId}, ${petServiceAccount.id.project}, ${petServiceAccount.serviceAccount.subjectId}, ${petServiceAccount.serviceAccount.email}, ${petServiceAccount.serviceAccount.displayName})"""
        .update().apply()
      petServiceAccount
    }
  }

  override def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId): IO[Option[PetServiceAccount]] = {
    runInTransaction() { implicit session =>
      val petServiceAccountTable = PetServiceAccountTable.syntax

      val loadPetQuery = samsql"""select ${petServiceAccountTable.resultAll}
       from ${PetServiceAccountTable as petServiceAccountTable}
       where ${petServiceAccountTable.userId} = ${petServiceAccountId.userId} and ${petServiceAccountTable.project} = ${petServiceAccountId.project}"""

      val petRecordOpt = loadPetQuery.map(PetServiceAccountTable(petServiceAccountTable)).single().apply()
      petRecordOpt.map(unmarshalPetServiceAccountRecord)
    }
  }

  override def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId): IO[Unit] = {
    runInTransaction() { implicit session =>
      val petServiceAccountTable = PetServiceAccountTable.syntax
      val deletePetQuery = samsql"delete from ${PetServiceAccountTable.table} where ${petServiceAccountTable.userId} = ${petServiceAccountId.userId} and ${petServiceAccountTable.project} = ${petServiceAccountId.project}"
      if (deletePetQuery.update().apply() != 1) {
        throw new WorkbenchException(s"${petServiceAccountId} cannot be deleted because it already does not exist")
      }
    }
  }

  override def getAllPetServiceAccountsForUser(userId: WorkbenchUserId): IO[Seq[PetServiceAccount]] = {
    runInTransaction() { implicit session =>
      val petServiceAccountTable = PetServiceAccountTable.syntax

      val loadPetsQuery = samsql"""select ${petServiceAccountTable.resultAll}
                from ${PetServiceAccountTable as petServiceAccountTable} where ${petServiceAccountTable.userId} = ${userId}"""

      val petRecords = loadPetsQuery.map(PetServiceAccountTable(petServiceAccountTable)).list().apply()
      petRecords.map(unmarshalPetServiceAccountRecord)
    }
  }

  override def updatePetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount] = {
    runInTransaction() { implicit session =>
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

  case class SubjectConglomerate(
                                  userId: Option[WorkbenchUserId],
                                  groupName: Option[WorkbenchGroupName],
                                  petUserId: Option[WorkbenchUserId],
                                  petProject: Option[GoogleProject],
                                  policyResourceType: Option[ResourceTypeName],
                                  policyResourceId: Option[ResourceId],
                                  policyName: Option[AccessPolicyName])

  private def unmarshalSubjectConglomerate(subjectConglomerate: SubjectConglomerate): WorkbenchSubject = {
    subjectConglomerate match {
      case SubjectConglomerate(Some(userId), None, None, None, None, None, None) => userId
      case SubjectConglomerate(None, Some(groupName), None, None, None, None, None) => groupName
      case SubjectConglomerate(None, None, Some(petUserId), Some(petProject), None, None, None) => PetServiceAccountId(petUserId, petProject)
      case SubjectConglomerate(None, None, None, None, Some(policyResourceType), Some(policyResourceId), Some(policyName)) => FullyQualifiedPolicyId(FullyQualifiedResourceId(policyResourceType, policyResourceId), policyName)
      case _ => throw new WorkbenchException("Not found")
    }
  }

  override def getManagedGroupAccessInstructions(groupName: WorkbenchGroupName): IO[Option[String]] = {
    runInTransaction() { implicit session =>
      val groupTable = GroupTable.syntax
      val accessInstructionsTable = AccessInstructionsTable.syntax

      // note the left join - this allows us to distinguish between the group does not exist and the group exists but
      // does not have access instructions
      val loadAccessInstructionsQuery = samsql"""select ${accessInstructionsTable.resultAll}
                from ${GroupTable as groupTable}
                left join ${AccessInstructionsTable as accessInstructionsTable} on ${groupTable.id} = ${accessInstructionsTable.groupId}
                where ${groupTable.name} = ${groupName}"""

      val accessInstructionsOpt = loadAccessInstructionsQuery.map(AccessInstructionsTable(accessInstructionsTable)).single().apply()

      accessInstructionsOpt match {
        case None =>
          // the group does not exist
          throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"$groupName not found"))
        case Some(accessInstructionsRecord) =>
          // the group exists but the access instructions will be null in the query result if there are no instructions
          Option(accessInstructionsRecord.instructions)
      }
    }
  }

  override def setManagedGroupAccessInstructions(groupName: WorkbenchGroupName, accessInstructions: String): IO[Unit] = {
    runInTransaction() { implicit session =>
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

  override def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId): IO[Unit] = {
    runInTransaction() { implicit session =>
      val u = UserTable.column
      samsql"update ${UserTable.table} set ${u.googleSubjectId} = ${googleSubjectId} where ${u.id} = ${userId}".update().apply()
    }
  }
}
