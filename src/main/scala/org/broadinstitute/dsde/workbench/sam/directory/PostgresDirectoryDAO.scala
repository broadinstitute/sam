package org.broadinstitute.dsde.workbench.sam.directory

import java.time.Instant
import java.util.Date

import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountSubjectId
import org.broadinstitute.dsde.workbench.sam.db._
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, FullyQualifiedPolicyId}
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
    GroupPK(withSQL {
      insert
        .into(GroupTable)
        .namedValues(
          groupTableColumn.name -> group.id, // the id of a BasicWorkbenchGroup is the name of the group and is a different id from the database id... obviously (sorry)
          groupTableColumn.email -> group.email,
          groupTableColumn.updatedDate -> Option(Instant.now()),
          groupTableColumn.synchronizedDate -> None
        )
    }.updateAndReturnGeneratedKey().apply())
  }

  private def insertAccessInstructions(groupId: GroupPK, accessInstructions: String)(implicit session: DBSession): Int = {
    val accessInstructionsColumn = AccessInstructionsTable.column
    withSQL {
      insert
        .into(AccessInstructionsTable)
        .namedValues(
          accessInstructionsColumn.groupId -> groupId,
          accessInstructionsColumn.instructions -> accessInstructions
        )
    }.update().apply()
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
    runInTransaction { implicit session =>
      val groupTable = GroupTable.syntax("g")

      import SamTypeBinders._
      withSQL {
        select(groupTable.email)
          .from(GroupTable as groupTable)
          .where
          .eq(groupTable.name, groupName)
      }.map(rs => rs.get[WorkbenchEmail]("email")).single().apply()
    }
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
          sql"insert into ${GroupMemberTable.table} (${groupMemberColumn.groupId}, ${groupMemberColumn.memberUserId}) values (($groupPKQuery), ${memberUser.value})"
        case memberGroup: WorkbenchGroupIdentity =>
          val memberGroupPKQuery = workbenchGroupIdentityToGroupPK(memberGroup)
          sql"insert into ${GroupMemberTable.table} (${groupMemberColumn.groupId}, ${groupMemberColumn.memberGroupId}) values ((${groupPKQuery}), (${memberGroupPKQuery}))"
        case _ => throw new WorkbenchException(s"unexpected WorkbenchSubject $addMember")
      }
      addMemberQuery.update().apply() > 0
    }
  }

  private def workbenchGroupIdentityToGroupPK(groupId: WorkbenchGroupIdentity): SQLSyntax = {
    groupId match {
      case group: WorkbenchGroupName => groupPKQueryForGroup(group)
      case policy: FullyQualifiedPolicyId => groupPKQueryForPolicy(policy)
      case _ => throw new WorkbenchException(s"unexpected WorkbenchGroupIdentity $groupId")
    }
  }

  private def groupPKQueryForGroup(groupName: WorkbenchGroupName): SQLSyntax = {
    val groupTable = GroupTable.syntax("gpk")

    sqls"SELECT ${groupTable.id} from ${GroupTable.as(groupTable)} where ${groupTable.name} = ${groupName.value}"
  }

  private def groupPKQueryForPolicy(policyId: FullyQualifiedPolicyId): SQLSyntax = {
    val resourceTable = ResourceTable.syntax("r")
    val policyTable = PolicyTable.syntax("p")
    // TODO: untested, needs to be done after implementing access policy DAO
    sqls"""SELECT ${policyTable.groupId} from ${PolicyTable.as(policyTable)}
           join ${ResourceTable.as(resourceTable)} on ${policyTable.resourceId} = ${resourceTable.id}
           where ${policyTable.name} = ${policyId.accessPolicyName.value}
           and ${resourceTable.name} = ${policyId.resource.resourceId}"""
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
          sql"delete from ${GroupMemberTable.table} where ${groupMemberColumn.groupId} = (${groupPKQuery}) and ${groupMemberColumn.memberUserId} = ${memberUser.value}"
        case memberGroup: WorkbenchGroupIdentity =>
          val memberGroupPKQuery = workbenchGroupIdentityToGroupPK(memberGroup)
          sql"delete from ${GroupMemberTable.table} where ${groupMemberColumn.groupId} = (${groupPKQuery}) and ${groupMemberColumn.memberGroupId} = (${memberGroupPKQuery})"
        case _ => throw new WorkbenchException(s"unexpected WorkbenchSubject $removeMember")
      }
      removeMemberQuery.update().apply() > 0
    }
  }

  override def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject): IO[Boolean] = ???

  override def updateSynchronizedDate(groupId: WorkbenchGroupIdentity): IO[Unit] = ???

  override def getSynchronizedDate(groupId: WorkbenchGroupIdentity): IO[Option[Date]] = ???

  override def getSynchronizedEmail(groupId: WorkbenchGroupIdentity): IO[Option[WorkbenchEmail]] = ???

  override def loadSubjectFromEmail(email: WorkbenchEmail): IO[Option[WorkbenchSubject]] = ???

  override def loadSubjectEmail(subject: WorkbenchSubject): IO[Option[WorkbenchEmail]] = ???

  override def loadSubjectEmails(subjects: Set[WorkbenchSubject]): IO[Stream[WorkbenchEmail]] = ???

  override def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId): IO[Option[WorkbenchSubject]] = ???

  override def createUser(user: WorkbenchUser): IO[WorkbenchUser] = {
    runInTransaction { implicit session =>
      val userColumn = UserTable.column

      val insertUserQuery = sql"insert into ${UserTable.table} (${userColumn.id}, ${userColumn.email}, ${userColumn.googleSubjectId}) values (${user.id.value}, ${user.email.value}, ${user.googleSubjectId.map(_.value)})"
      insertUserQuery.update.apply
      user
    }
  }

  override def loadUser(userId: WorkbenchUserId): IO[Option[WorkbenchUser]] = {
    runInTransaction { implicit session =>
      val userTable = UserTable.syntax
      val userColumn = UserTable.column

      import SamTypeBinders._
      val loadUserQuery = sql"select ${userTable.id}, ${userTable.email}, ${userTable.googleSubjectId} from ${UserTable.table} where ${userTable.id} = ${userId.value}"
      loadUserQuery.map(rs => WorkbenchUser(rs.get[WorkbenchUserId](userColumn.id), rs.stringOpt(userColumn.googleSubjectId).map(GoogleSubjectId), rs.get[WorkbenchEmail](userColumn.email)))
        .single().apply()
    }
  }

  override def loadUsers(userIds: Set[WorkbenchUserId]): IO[Stream[WorkbenchUser]] = {
    runInTransaction { implicit session =>
      val userTable = UserTable.syntax
      val userColumn = UserTable.column

      import SamTypeBinders._

      val loadUsersQuery = sql"select ${userTable.id}, ${userTable.email}, ${userTable.googleSubjectId} from ${UserTable.table} where ${userTable.id} in (${userIds.map(_.value)})"
      loadUsersQuery.map(rs => WorkbenchUser(rs.get[WorkbenchUserId](userColumn.id), rs.stringOpt(userColumn.googleSubjectId).map(GoogleSubjectId), rs.get[WorkbenchEmail](userColumn.email)))
        .list().apply().toStream
    }
  }

  // Not worrying about cascading deletion of user's pet SAs because LDAP doesn't delete user's pet SAs automatically
  override def deleteUser(userId: WorkbenchUserId): IO[Unit] = {
    runInTransaction { implicit session =>
      val userTable = UserTable.syntax
      sql"delete from ${UserTable.table} where ${userTable.id} = ${userId.value}".update().apply()
    }
  }

  override def addProxyGroup(userId: WorkbenchUserId, proxyEmail: WorkbenchEmail): IO[Unit] = ???

  override def readProxyGroup(userId: WorkbenchUserId): IO[Option[WorkbenchEmail]] = ???

  override def listUsersGroups(userId: WorkbenchUserId): IO[Set[WorkbenchGroupIdentity]] = ???

  override def listUserDirectMemberships(userId: WorkbenchUserId): IO[Stream[WorkbenchGroupIdentity]] = ???

  override def listIntersectionGroupUsers(groupId: Set[WorkbenchGroupIdentity]): IO[Set[WorkbenchUserId]] = ???

  override def listAncestorGroups(groupId: WorkbenchGroupIdentity): IO[Set[WorkbenchGroupIdentity]] = ???

  override def enableIdentity(subject: WorkbenchSubject): IO[Unit] = ???

  override def disableIdentity(subject: WorkbenchSubject): IO[Unit] = ???

  override def isEnabled(subject: WorkbenchSubject): IO[Boolean] = ???

  override def getUserFromPetServiceAccount(petSA: ServiceAccountSubjectId): IO[Option[WorkbenchUser]] = ???

  override def createPetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount] = ???

  override def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId): IO[Option[PetServiceAccount]] = ???

  override def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId): IO[Unit] = ???

  override def getAllPetServiceAccountsForUser(userId: WorkbenchUserId): IO[Seq[PetServiceAccount]] = ???

  override def updatePetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount] = ???

  override def getManagedGroupAccessInstructions(groupName: WorkbenchGroupName): IO[Option[String]] = ???

  override def setManagedGroupAccessInstructions(groupName: WorkbenchGroupName, accessInstructions: String): IO[Unit] = ???

  override def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId): IO[Unit] = ???
}
