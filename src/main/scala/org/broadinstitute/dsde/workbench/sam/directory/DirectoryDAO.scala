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

  def createUser(user: WorkbenchUser): Future[WorkbenchUser]
  def loadUser(userId: WorkbenchUserId): Future[Option[WorkbenchUser]]
  def loadUsers(userIds: Set[WorkbenchUserId]): Future[Seq[WorkbenchUser]]
  def deleteUser(userId: WorkbenchUserId): Future[Unit]

  def listUsersGroups(userId: WorkbenchUserId): Future[Set[WorkbenchGroupName]]
  def listFlattenedGroupUsers(groupName: WorkbenchGroupName): Future[Set[WorkbenchUserId]]
  def listAncestorGroups(groupName: WorkbenchGroupName): Future[Set[WorkbenchGroupName]]

  def enableIdentity(subject: WorkbenchSubject): Future[Unit]
  def disableIdentity(subject: WorkbenchSubject): Future[Unit]
  def isEnabled(subject: WorkbenchSubject): Future[Boolean]

  def createPetServiceAccount(petServiceAccount: WorkbenchUserServiceAccount): Future[WorkbenchUserServiceAccount]
  def loadPetServiceAccount(petServiceAccountId: WorkbenchUserServiceAccountSubjectId): Future[Option[WorkbenchUserServiceAccount]]
  def deletePetServiceAccount(petServiceAccountId: WorkbenchUserServiceAccountSubjectId): Future[Unit]
  def getPetServiceAccountForUser(userId: WorkbenchUserId): Future[Option[WorkbenchUserServiceAccountEmail]]
  def addPetServiceAccountToUser(userId: WorkbenchUserId, petServiceAccountEmail: WorkbenchUserServiceAccountEmail): Future[WorkbenchUserServiceAccountEmail]
  def removePetServiceAccountFromUser(userId: WorkbenchUserId): Future[Unit]
  def getUserFromPetServiceAccount(petSA:WorkbenchUserServiceAccountEmail):Future[Option[WorkbenchUser]]
}
