package org.broadinstitute.dsde.workbench.sam.directory

import org.broadinstitute.dsde.workbench.model._

import scala.concurrent.Future

/**
  * Created by dvoet on 5/26/17.
  */
trait DirectoryDAO {
  def createGroup(group: WorkbenchGroup): Future[WorkbenchGroup]
  def loadGroup(groupName: WorkbenchGroupName): Future[Option[WorkbenchGroup]]
  def loadGroups(groupNames: Set[WorkbenchGroupName]): Future[Seq[WorkbenchGroup]]
  def loadGroupEmail(groupName: WorkbenchGroupName): Future[Option[WorkbenchGroupEmail]]
  def deleteGroup(groupName: WorkbenchGroupName): Future[Unit]
  def addGroupMember(groupName: WorkbenchGroupName, addMember: WorkbenchSubject): Future[Unit]
  def removeGroupMember(groupName: WorkbenchGroupName, removeMember: WorkbenchSubject): Future[Unit]
  def isGroupMember(groupName: WorkbenchGroupName, member: WorkbenchSubject): Future[Boolean]

  def loadSubjectFromEmail(email: String): Future[Option[WorkbenchSubject]]

  def createUser(user: WorkbenchPerson): Future[WorkbenchPerson]
  def loadUser(userId: WorkbenchSubject): Future[Option[WorkbenchPerson]]
  def loadUsers(userIds: Set[WorkbenchSubject]): Future[Seq[WorkbenchPerson]]
  def deleteUser(userId: WorkbenchSubject): Future[Unit]

  def listUsersGroups(userId: WorkbenchSubject): Future[Set[WorkbenchGroupName]]
  def listFlattenedGroupUsers(groupName: WorkbenchGroupName): Future[Set[WorkbenchSubject]]
  def listAncestorGroups(groupName: WorkbenchGroupName): Future[Set[WorkbenchGroupName]]

  def enableUser(userId: WorkbenchSubject): Future[Unit]
  def disableUser(userId: WorkbenchSubject): Future[Unit]
  def isEnabled(userId: WorkbenchSubject): Future[Boolean]
  def getPetServiceAccountForUser(userId: WorkbenchUserId): Future[Option[WorkbenchUserServiceAccountEmail]]
  def addPetServiceAccountToUser(userId: WorkbenchUserId, email: WorkbenchUserServiceAccountEmail): Future[WorkbenchUserServiceAccountEmail]
  def removePetServiceAccountFromUser(userId: WorkbenchUserId): Future[Unit]
}
