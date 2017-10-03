package org.broadinstitute.dsde.workbench.sam.directory
import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model._

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import org.broadinstitute.dsde.workbench.sam._

/**
  * Created by mbemis on 6/23/17.
  */
class MockDirectoryDAO extends DirectoryDAO {
  private val groups: mutable.Map[WorkbenchGroupName, WorkbenchGroup] = new TrieMap()
  private val users: mutable.Map[WorkbenchUserId, WorkbenchUser] = new TrieMap()
  private val enabledUsers: mutable.Map[WorkbenchUserId, Unit] = new TrieMap()
  private val usersWithEmails: mutable.Map[WorkbenchUserEmail, WorkbenchUserId] = new TrieMap()
  private val groupsWithEmails: mutable.Map[WorkbenchGroupEmail, WorkbenchGroupName] = new TrieMap()

  override def createGroup(group: WorkbenchGroup): Future[WorkbenchGroup] = Future {
    if (groups.keySet.contains(group.name)) {
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group ${group.name} already exists"))
    }
    groups += group.name -> group
    groupsWithEmails += group.email -> group.name
    group
  }

  override def loadGroup(groupName: WorkbenchGroupName): Future[Option[WorkbenchGroup]] = Future {
    groups.get(groupName)
  }

  override def deleteGroup(groupName: WorkbenchGroupName): Future[Unit] = Future {
    groups -= groupName
  }

  override def addGroupMember(groupName: WorkbenchGroupName, addMember: WorkbenchSubject): Future[Unit] = Future {
    val group = groups(groupName)
    val updatedGroup = group.copy(members = group.members + addMember)
    groups += groupName -> updatedGroup
  }

  override def removeGroupMember(groupName: WorkbenchGroupName, removeMember: WorkbenchSubject): Future[Unit] = Future {
    val group = groups(groupName)
    val updatedGroup = group.copy(members = group.members - removeMember)
    groups += groupName -> updatedGroup
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

  override def deleteUser(userId: WorkbenchUserId): Future[Unit] = Future {
    users -= userId
  }

  override def listUsersGroups(userId: WorkbenchUserId): Future[Set[WorkbenchGroupName]] = Future {
    listSubjectsGroups(userId, Set.empty).map(_.name)
  }

  private def listSubjectsGroups(subject: WorkbenchSubject, accumulatedGroups: Set[WorkbenchGroup]): Set[WorkbenchGroup] = {
    val immediateGroups = groups.values.toSet.filter { group => group.members.contains(subject) }

    val unvisitedGroups = immediateGroups -- accumulatedGroups
    if (unvisitedGroups.isEmpty) {
      accumulatedGroups
    } else {
      unvisitedGroups.flatMap { group =>
        listSubjectsGroups(group.name, accumulatedGroups ++ immediateGroups)
      }
    }
  }

  override def listFlattenedGroupUsers(groupName: WorkbenchGroupName): Future[Set[WorkbenchUserId]] = Future {
    listGroupUsers(groupName, Set.empty)
  }

  override def isGroupMember(groupName: WorkbenchGroupName, member: WorkbenchSubject): Future[Boolean] = Future {
    groups.getOrElse(groupName, WorkbenchGroup(null, Set.empty, WorkbenchGroupEmail("g1@example.com"))).members.contains(member)
  }

  private def listGroupUsers(groupName: WorkbenchGroupName, visitedGroups: Set[WorkbenchGroupName]): Set[WorkbenchUserId] = {
    if (!visitedGroups.contains(groupName)) {
      val members = groups.getOrElse(groupName, WorkbenchGroup(null, Set.empty, WorkbenchGroupEmail("g1@example.com"))).members

      members.flatMap {
        case userId: WorkbenchUserId => Set(userId)
        case groupName: WorkbenchGroupName => listGroupUsers(groupName, visitedGroups + groupName)
      }
    } else {
      Set.empty
    }
  }

  override def listAncestorGroups(groupName: WorkbenchGroupName): Future[Set[WorkbenchGroupName]] = Future {
    listSubjectsGroups(groupName, Set.empty).map(_.name)
  }

  override def enableUser(userId: WorkbenchUserId): Future[Unit] = Future {
    enabledUsers += (userId -> ())
  }

  override def disableUser(userId: WorkbenchUserId): Future[Unit] = Future {
    enabledUsers -= userId
  }

  override def isEnabled(userId: WorkbenchUserId): Future[Boolean] = Future {
    enabledUsers.contains(userId)
  }

  override def loadSubjectFromEmail(email: String): Future[Option[WorkbenchSubject]] = Future {
    Option(usersWithEmails.getOrElse(WorkbenchUserEmail(email), groupsWithEmails.getOrElse(WorkbenchGroupEmail(email), null)))
  }
}
