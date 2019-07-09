package org.broadinstitute.dsde.workbench.sam.directory

import java.time.Instant
import java.util.Date

import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountSubjectId
import org.broadinstitute.dsde.workbench.sam.db._
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.util.DatabaseSupport
import scalikejdbc._
import SamParameterBinderFactory._

import scala.concurrent.ExecutionContext

class PostgresDirectoryDAO(protected val dbRef: DbReference,
                           protected val ecForDatabaseIO: ExecutionContext)(implicit executionContext: ExecutionContext) extends DirectoryDAO with DatabaseSupport {
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  override def createGroup(group: BasicWorkbenchGroup, accessInstructionsOpt: Option[String]): IO[BasicWorkbenchGroup] = {
    runInTransaction { implicit session =>
      val groupId: GroupId = insertGroup(group)

      accessInstructionsOpt.map { accessInstructions =>
        insertAccessInstructions(groupId, accessInstructions)
      }

      insertGroupMembers(groupId, group.members)

      group
    }
  }

  private def insertGroup(group: BasicWorkbenchGroup)(implicit session: DBSession): GroupId = {
    val groupTableColumn = GroupTable.column
    GroupId(withSQL {
      insert.into(GroupTable).namedValues(
        groupTableColumn.name -> group.id,
        groupTableColumn.email -> group.email,
        groupTableColumn.updatedDate -> Option(Instant.now()),
        groupTableColumn.synchronizedDate -> None
      )
    }.updateAndReturnGeneratedKey().apply())
  }

  private def insertAccessInstructions(groupId: GroupId, accessInstructions: String)(implicit session: DBSession): Int = {
    val accessInstructionsColumn = AccessInstructionsTable.column
    withSQL {
      insert.into(AccessInstructionsTable).namedValues(
        accessInstructionsColumn.groupId -> groupId,
        accessInstructionsColumn.accessInstructions -> accessInstructions
      )
    }.update().apply()
  }

  private def insertGroupMembers(groupId: GroupId, members: Set[WorkbenchSubject])(implicit session: DBSession): Int = {
    val memberUserRecords: Seq[(GroupId, Option[UserId], Option[GroupId])] = members.collect {
      case WorkbenchUserId(value) => (groupId, Option(UserId(value)), None)
    }.toSeq

    val memberGroupNames = members.collect {
      case WorkbenchGroupName(name) => name
    }

    val groupTable = GroupTable.syntax("group")
    val memberGroupRecords: Seq[(GroupId, Option[UserId], Option[GroupId])] = withSQL {
      select(groupTable.id).from(GroupTable as groupTable).where.in(groupTable.name, memberGroupNames.toSeq)
    }.map(rs => (groupId, None, Option(GroupId(rs.long(groupTable.id))))).list().apply()

    val allRecords = (memberUserRecords ++ memberGroupRecords).map(_.productIterator.toSeq)

    val groupMemberTable = GroupMemberTable.syntax("groupMember")
    withSQL {
      insert.into(GroupMemberTable).columns(groupMemberTable.groupId, groupMemberTable.memberUserId, groupMemberTable.memberGroupId).multipleValues(allRecords: _*)
    }.update().apply()
  }

  override def loadGroup(groupName: WorkbenchGroupName): IO[Option[BasicWorkbenchGroup]] = {
    runInTransaction { implicit session =>
      val groupTable = GroupTable.syntax("group")
      val groupMemberTable = GroupMemberTable.syntax("groupMember")

      import GroupMemberTableBinders._
      import GroupTableBinders._

      withSQL {
        select(groupTable.name, groupTable.email, groupMemberTable.memberGroupId)
          .from(GroupTable as groupTable)
          .innerJoin(GroupMemberTable as groupMemberTable)
          .on(groupTable.id, groupMemberTable.groupId)
          .where.eq(groupTable.name, WorkbenchGroupName(groupName.value))
      }.map(BasicWorkbenchGroup(groupTable.name, groupTable.email, )).list().apply()
    }
  }

  override def loadGroups(groupNames: Set[WorkbenchGroupName]): IO[Stream[BasicWorkbenchGroup]] = ???

  override def loadGroupEmail(groupName: WorkbenchGroupName): IO[Option[WorkbenchEmail]] = ???

  override def batchLoadGroupEmail(groupNames: Set[WorkbenchGroupName]): IO[Stream[(WorkbenchGroupName, WorkbenchEmail)]] = ???

  override def deleteGroup(groupName: WorkbenchGroupName): IO[Unit] = ???

  /**
    * @return true if the subject was added, false if it was already there
    */
  override def addGroupMember(groupId: WorkbenchGroupIdentity, addMember: WorkbenchSubject): IO[Boolean] = ???

  /**
    * @return true if the subject was removed, false if it was already gone
    */
  override def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject): IO[Boolean] = ???

  override def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject): IO[Boolean] = ???

  override def updateSynchronizedDate(groupId: WorkbenchGroupIdentity): IO[Unit] = ???

  override def getSynchronizedDate(groupId: WorkbenchGroupIdentity): IO[Option[Date]] = ???

  override def getSynchronizedEmail(groupId: WorkbenchGroupIdentity): IO[Option[WorkbenchEmail]] = ???

  override def loadSubjectFromEmail(email: WorkbenchEmail): IO[Option[WorkbenchSubject]] = ???

  override def loadSubjectEmail(subject: WorkbenchSubject): IO[Option[WorkbenchEmail]] = ???

  override def loadSubjectEmails(subjects: Set[WorkbenchSubject]): IO[Stream[WorkbenchEmail]] = ???

  override def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId): IO[Option[WorkbenchSubject]] = ???

  override def createUser(user: WorkbenchUser): IO[WorkbenchUser] = ???

  override def loadUser(userId: WorkbenchUserId): IO[Option[WorkbenchUser]] = ???

  override def loadUsers(userIds: Set[WorkbenchUserId]): IO[Stream[WorkbenchUser]] = ???

  override def deleteUser(userId: WorkbenchUserId): IO[Unit] = ???

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
