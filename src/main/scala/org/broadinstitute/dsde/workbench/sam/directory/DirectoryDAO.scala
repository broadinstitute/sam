package org.broadinstitute.dsde.workbench.sam.directory

import org.broadinstitute.dsde.workbench.sam.model._

import scala.concurrent.Future

/**
  * Created by dvoet on 5/26/17.
  */
trait DirectoryDAO {
  def createGroup(group: SamGroup): Future[SamGroup]
  def loadGroup(groupName: SamGroupName): Future[Option[SamGroup]]
  def deleteGroup(groupName: SamGroupName): Future[Unit]
  def addGroupMember(groupName: SamGroupName, addMember: SamSubject): Future[Unit]
  def removeGroupMember(groupName: SamGroupName, removeMember: SamSubject): Future[Unit]

  def createUser(user: SamUser): Future[SamUser]
  def loadUser(userId: SamUserId): Future[Option[SamUser]]
  def deleteUser(userId: SamUserId): Future[Unit]

  def listUsersGroups(userId: SamUserId): Future[Set[SamGroupName]]
}
