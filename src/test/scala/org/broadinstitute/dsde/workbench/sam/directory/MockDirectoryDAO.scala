package org.broadinstitute.dsde.workbench.sam.directory
import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.sam.WorkbenchExceptionWithErrorReport
import org.broadinstitute.dsde.workbench.sam.model._

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
/**
  * Created by mbemis on 6/23/17.
  */
class MockDirectoryDAO extends DirectoryDAO {
  private val groups: mutable.Map[SamGroupName, SamGroup] = new TrieMap()
  private val users: mutable.Map[SamUserId, SamUser] = new TrieMap()

  override def createGroup(group: SamGroup): Future[SamGroup] = Future {
    if (groups.keySet.contains(group.name)) {
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group ${group.name} already exists"))
    }
    groups += group.name -> group
    group
  }

  override def loadGroup(groupName: SamGroupName): Future[Option[SamGroup]] = Future {
    groups.get(groupName)
  }

  override def deleteGroup(groupName: SamGroupName): Future[Unit] = Future {
    groups -= groupName
  }

  override def addGroupMember(groupName: SamGroupName, addMember: SamSubject): Future[Unit] = Future {
    val group = groups(groupName)
    val updatedGroup = group.copy(members = group.members + addMember)
    groups += groupName -> updatedGroup
  }

  override def removeGroupMember(groupName: SamGroupName, removeMember: SamSubject): Future[Unit] = Future {
    val group = groups(groupName)
    val updatedGroup = group.copy(members = group.members - removeMember)
    groups += groupName -> updatedGroup
  }

  override def createUser(user: SamUser): Future[SamUser] = Future {
    if (users.keySet.contains(user.id)) {
      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"user ${user.id} already exists"))
    }
    users += user.id -> user
    user
  }

  override def loadUser(userId: SamUserId): Future[Option[SamUser]] = Future {
    users.get(userId)
  }

  override def deleteUser(userId: SamUserId): Future[Unit] = Future {
    users -= userId
  }

  override def listUsersGroups(userId: SamUserId): Future[Set[SamGroupName]] = Future {
    listSubjectsGroups(userId, Set.empty).map(_.name)
  }

  private def listSubjectsGroups(subject: SamSubject, accumulatedGroups: Set[SamGroup]): Set[SamGroup] = {
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

  override def listFlattenedGroupUsers(groupName: SamGroupName): Future[Set[SamUserId]] = Future {
    listGroupUsers(groupName, Set.empty)
  }

  private def listGroupUsers(groupName: SamGroupName, visitedGroups: Set[SamGroupName]): Set[SamUserId] = {
    if (!visitedGroups.contains(groupName)) {
      val members = groups.getOrElse(groupName, SamGroup(null, Set.empty)).members

      members.flatMap {
        case userId: SamUserId => Set(userId)
        case groupName: SamGroupName => listGroupUsers(groupName, visitedGroups + groupName)
      }
    } else {
      Set.empty
    }
  }
}
