package org.broadinstitute.dsde.workbench.sam.directory

import java.util.Date

import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup

import scala.concurrent.Future

/**
  * Created by dvoet on 5/26/17.
  */
trait DirectoryDAO {
  def createGroup(group: BasicWorkbenchGroup): Future[BasicWorkbenchGroup]
  def loadGroup(groupName: WorkbenchGroupName): Future[Option[BasicWorkbenchGroup]]
  def loadGroups(groupNames: Set[WorkbenchGroupName]): Future[Seq[BasicWorkbenchGroup]]
  def loadGroupEmail(groupName: WorkbenchGroupName): Future[Option[WorkbenchGroupEmail]]
  def deleteGroup(groupName: WorkbenchGroupName): Future[Unit]

  def addGroupMember(groupId: WorkbenchGroupIdentity, addMember: WorkbenchSubject): Future[Unit]
  def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject): Future[Unit]
  def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject): Future[Boolean]
  def updateSynchronizedDate(groupId: WorkbenchGroupIdentity): Future[Unit]
  def getSynchronizedDate(groupId: WorkbenchGroupIdentity): Future[Option[Date]]

  def loadSubjectFromEmail(email: String): Future[Option[WorkbenchSubject]]
  def loadSubjectEmail(subject: WorkbenchSubject): Future[Option[WorkbenchEmail]]

  def createUser(user: WorkbenchUser): Future[WorkbenchUser]
  def loadUser(userId: WorkbenchUserId): Future[Option[WorkbenchUser]]
  def loadUsers(userIds: Set[WorkbenchUserId]): Future[Seq[WorkbenchUser]]
  def deleteUser(userId: WorkbenchUserId): Future[Unit]

  def listUsersGroups(userId: WorkbenchUserId): Future[Set[WorkbenchGroupIdentity]]
  def listFlattenedGroupUsers(groupId: WorkbenchGroupIdentity): Future[Set[WorkbenchUserId]]
  def listAncestorGroups(groupId: WorkbenchGroupIdentity): Future[Set[WorkbenchGroupIdentity]]

  def enableIdentity(subject: WorkbenchSubject): Future[Unit]
  def disableIdentity(subject: WorkbenchSubject): Future[Unit]
  def isEnabled(subject: WorkbenchSubject): Future[Boolean]

  def createPetServiceAccount(petServiceAccount: WorkbenchUserServiceAccount): Future[WorkbenchUserServiceAccount]
  def loadPetServiceAccount(petServiceAccountId: WorkbenchUserServiceAccountSubjectId): Future[Option[WorkbenchUserServiceAccount]]
  def deletePetServiceAccount(petServiceAccountId: WorkbenchUserServiceAccountSubjectId): Future[Unit]
  def getPetServiceAccountForUser(userId: WorkbenchUserId): Future[Option[WorkbenchUserServiceAccountEmail]]
  def addPetServiceAccountToUser(userId: WorkbenchUserId, petServiceAccountEmail: WorkbenchUserServiceAccountEmail): Future[WorkbenchUserServiceAccountEmail]
  def removePetServiceAccountFromUser(userId: WorkbenchUserId): Future[Unit]
}
