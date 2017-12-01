package org.broadinstitute.dsde.workbench.sam.directory

import java.util.Date

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model._

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicy, BasicWorkbenchGroup}

/**
  * Created by mbemis on 6/23/17.
  */
class MockDirectoryDAO(private val groups: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = new TrieMap()) extends DirectoryDAO {
  private val groupSynchronzedDates: mutable.Map[WorkbenchGroupIdentity, Date] = new TrieMap()
  private val users: mutable.Map[WorkbenchUserId, WorkbenchUser] = new TrieMap()
  private val enabledUsers: mutable.Map[WorkbenchSubject, Unit] = new TrieMap()
  private val usersWithEmails: mutable.Map[WorkbenchEmail, WorkbenchUserId] = new TrieMap()
  private val groupsWithEmails: mutable.Map[WorkbenchEmail, WorkbenchGroupName] = new TrieMap()
  private val petServiceAccountsByUser: mutable.Map[PetServiceAccountId, PetServiceAccount] = new TrieMap()

  override def createGroup(group: BasicWorkbenchGroup): Future[BasicWorkbenchGroup] = Future {
    if (groups.keySet.contains(group.id)) {
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group ${group.id} already exists"))
    }
    groups += group.id -> group
    groupsWithEmails += group.email -> group.id
    group
  }

  override def loadGroup(groupName: WorkbenchGroupName): Future[Option[BasicWorkbenchGroup]] = Future {
    groups.get(groupName).map(_.asInstanceOf[BasicWorkbenchGroup])
  }

  override def loadGroups(groupNames: Set[WorkbenchGroupName]): Future[Seq[BasicWorkbenchGroup]] = Future {
    groups.filterKeys(groupNames.map(_.asInstanceOf[WorkbenchGroupIdentity])).values.map(_.asInstanceOf[BasicWorkbenchGroup]).toSeq
  }

  override def deleteGroup(groupName: WorkbenchGroupName): Future[Unit] = Future {
    groups -= groupName
  }

  override def addGroupMember(groupName: WorkbenchGroupIdentity, addMember: WorkbenchSubject): Future[Unit] = Future {
    val group = groups(groupName)
    val updatedGroup = group match {
      case g: BasicWorkbenchGroup => g.copy(members = group.members + addMember)
      case p: AccessPolicy => p.copy(members = group.members + addMember)
      case _ => throw new WorkbenchException(s"unknown group implementation: $group")
    }
    groups += groupName -> updatedGroup
  }

  override def removeGroupMember(groupName: WorkbenchGroupIdentity, removeMember: WorkbenchSubject): Future[Unit] = Future {
    val group = groups(groupName)
    val updatedGroup = group match {
      case g: BasicWorkbenchGroup => g.copy(members = group.members - removeMember)
      case p: AccessPolicy => p.copy(members = group.members - removeMember)
      case _ => throw new WorkbenchException(s"unknown group implementation: $group")
    }
    groups += groupName -> updatedGroup
  }

  override def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject): Future[Boolean] = Future {
    groups.getOrElse(groupId, BasicWorkbenchGroup(null, Set.empty, WorkbenchEmail("g1@example.com"))).members.contains(member)
  }

  override def loadSubjectFromEmail(email: String): Future[Option[WorkbenchSubject]] = Future {
    Option(usersWithEmails.getOrElse(WorkbenchEmail(email), groupsWithEmails.getOrElse(WorkbenchEmail(email), null)))
  }

  override def createUser(user: WorkbenchUser): Future[WorkbenchUser] = Future {
    if (users.keySet.contains(user.id)) {
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"user ${user.id} already exists"))
    }
    users += user.id -> user
    usersWithEmails += user.email -> user.id
    user
  }

  override def loadUser(userId: WorkbenchUserId): Future[Option[WorkbenchUser]] = Future {
    users.get(userId)
  }

  override def loadUsers(userIds: Set[WorkbenchUserId]): Future[Seq[WorkbenchUser]] = Future {
    users.filterKeys(userIds).values.toSeq
  }

  override def deleteUser(userId: WorkbenchUserId): Future[Unit] = Future {
    users -= userId
  }

  override def listUsersGroups(userId: WorkbenchUserId): Future[Set[WorkbenchGroupIdentity]] = Future {
    listSubjectsGroups(userId, Set.empty).map(_.id)
  }

  private def listSubjectsGroups(subject: WorkbenchSubject, accumulatedGroups: Set[WorkbenchGroup]): Set[WorkbenchGroup] = {
    val immediateGroups = groups.values.toSet.filter { group => group.members.contains(subject) }

    val unvisitedGroups = immediateGroups -- accumulatedGroups
    if (unvisitedGroups.isEmpty) {
      accumulatedGroups
    } else {
      unvisitedGroups.flatMap { group =>
        listSubjectsGroups(group.id, accumulatedGroups ++ immediateGroups)
      }
    }
  }

  override def listFlattenedGroupUsers(groupName: WorkbenchGroupIdentity): Future[Set[WorkbenchUserId]] = Future {
    listGroupUsers(groupName, Set.empty)
  }

  private def listGroupUsers(groupName: WorkbenchGroupIdentity, visitedGroups: Set[WorkbenchGroupIdentity]): Set[WorkbenchUserId] = {
    if (!visitedGroups.contains(groupName)) {
      val members = groups.getOrElse(groupName, BasicWorkbenchGroup(null, Set.empty, WorkbenchEmail("g1@example.com"))).members

      members.flatMap {
        case userId: WorkbenchUserId => Set(userId)
        case groupName: WorkbenchGroupIdentity => listGroupUsers(groupName, visitedGroups + groupName)
        case petSubjectId: PetServiceAccountId => throw new WorkbenchException(s"Unexpected service account $petSubjectId")
      }
    } else {
      Set.empty
    }
  }

  override def listAncestorGroups(groupName: WorkbenchGroupIdentity): Future[Set[WorkbenchGroupIdentity]] = Future {
    listSubjectsGroups(groupName, Set.empty).map(_.id)
  }

  override def enableIdentity(subject: WorkbenchSubject): Future[Unit] = Future {
    enabledUsers += (subject -> ())
  }

  override def disableIdentity(subject: WorkbenchSubject): Future[Unit] = Future {
    enabledUsers -= subject
  }

  override def isEnabled(subject: WorkbenchSubject): Future[Boolean] = Future {
    enabledUsers.contains(subject)
  }

  override def loadGroupEmail(groupName: WorkbenchGroupName): Future[Option[WorkbenchEmail]] = loadGroup(groupName).map(_.map(_.email))

  override def createPetServiceAccount(petServiceAccount: PetServiceAccount): Future[PetServiceAccount] = Future {
    if (petServiceAccountsByUser.keySet.contains(petServiceAccount.id)) {
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"pet service account ${petServiceAccount.id} already exists"))
    }
    petServiceAccountsByUser += petServiceAccount.id -> petServiceAccount
    petServiceAccount
  }

  override def loadPetServiceAccount(petServiceAccountUniqueId: PetServiceAccountId): Future[Option[PetServiceAccount]] = Future {
    petServiceAccountsByUser.get(petServiceAccountUniqueId)
  }

  override def deletePetServiceAccount(petServiceAccountUniqueId: PetServiceAccountId): Future[Unit] = Future {
    petServiceAccountsByUser -= petServiceAccountUniqueId
  }

  override def getAllPetServiceAccountsForUser(userId: WorkbenchUserId): Future[Seq[PetServiceAccount]] = Future {
    petServiceAccountsByUser.collect {
      case (PetServiceAccountId(`userId`, _), petSA) => petSA
    }.toSeq
  }

  override def updateSynchronizedDate(groupId: WorkbenchGroupIdentity): Future[Unit] = {
    groupSynchronzedDates += groupId -> new Date()
    Future.successful(())
  }

  override def loadSubjectEmail(subject: WorkbenchSubject): Future[Option[WorkbenchEmail]] = Future {
    subject match {
      case id: WorkbenchUserId => users.get(id).map(_.email)
      case id: WorkbenchGroupIdentity => groups.get(id).map(_.email)
      case id: PetServiceAccountId => petServiceAccountsByUser.get(id).map(_.serviceAccount.email)
    }
  }

  override def getSynchronizedDate(groupId: WorkbenchGroupIdentity): Future[Option[Date]] = {
    Future.successful(groupSynchronzedDates.get(groupId))
  }
}
