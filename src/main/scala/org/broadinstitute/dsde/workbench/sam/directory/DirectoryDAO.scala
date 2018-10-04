package org.broadinstitute.dsde.workbench.sam.directory

import java.util.Date

import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup

import scala.concurrent.Future

/**
  * Created by dvoet on 5/26/17.
  */
trait DirectoryDAO {
  def createGroup(group: BasicWorkbenchGroup, accessInstructionsOpt: Option[String] = None): Future[BasicWorkbenchGroup]
  def loadGroup(groupName: WorkbenchGroupName): Future[Option[BasicWorkbenchGroup]]
  def loadGroups(groupNames: Set[WorkbenchGroupName]): Future[Seq[BasicWorkbenchGroup]]
  def loadGroupEmail(groupName: WorkbenchGroupName): Future[Option[WorkbenchEmail]]
  def batchLoadGroupEmail(groupNames: Set[WorkbenchGroupName]): Future[Seq[(WorkbenchGroupName, WorkbenchEmail)]]
  def deleteGroup(groupName: WorkbenchGroupName): Future[Unit]

  /**
    * @return true if the subject was added, false if it was already there
    */
  def addGroupMember(groupId: WorkbenchGroupIdentity, addMember: WorkbenchSubject): Future[Boolean]
  /**
    * @return true if the subject was removed, false if it was already gone
    */
  def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject): Future[Boolean]
  def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject): Future[Boolean]
  def updateSynchronizedDate(groupId: WorkbenchGroupIdentity): Future[Unit]
  def getSynchronizedDate(groupId: WorkbenchGroupIdentity): Future[Option[Date]]
  def getSynchronizedEmail(groupId: WorkbenchGroupIdentity): Future[Option[WorkbenchEmail]]

  def loadSubjectFromEmail(email: WorkbenchEmail): Future[Option[WorkbenchSubject]]
  def loadSubjectEmail(subject: WorkbenchSubject): Future[Option[WorkbenchEmail]]
  def loadSubjectEmails(subjects: Set[WorkbenchSubject]): Future[Set[WorkbenchEmail]]
  def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId): Future[Option[WorkbenchSubject]]

  def createUser(user: WorkbenchUser): Future[WorkbenchUser]
  def loadUser(userId: WorkbenchUserId): Future[Option[WorkbenchUser]]
  def loadUsers(userIds: Set[WorkbenchUserId]): Future[Seq[WorkbenchUser]]
  def deleteUser(userId: WorkbenchUserId): Future[Unit]
  def addProxyGroup(userId: WorkbenchUserId, proxyEmail: WorkbenchEmail): Future[Unit]
  def readProxyGroup(userId: WorkbenchUserId): Future[Option[WorkbenchEmail]]

  def listUsersGroups(userId: WorkbenchUserId): Future[Set[WorkbenchGroupIdentity]]
  def listIntersectionGroupUsers(groupId: Set[WorkbenchGroupIdentity]): Future[Set[WorkbenchUserId]]
  def listAncestorGroups(groupId: WorkbenchGroupIdentity): Future[Set[WorkbenchGroupIdentity]]

  def enableIdentity(subject: WorkbenchSubject): Future[Unit]
  def disableIdentity(subject: WorkbenchSubject): Future[Unit]
  def isEnabled(subject: WorkbenchSubject): Future[Boolean]

  def getUserFromPetServiceAccount(petSA: ServiceAccountSubjectId):Future[Option[WorkbenchUser]]
  def createPetServiceAccount(petServiceAccount: PetServiceAccount): Future[PetServiceAccount]
  def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId): Future[Option[PetServiceAccount]]
  def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId): Future[Unit]
  def getAllPetServiceAccountsForUser(userId: WorkbenchUserId): Future[Seq[PetServiceAccount]]
  def updatePetServiceAccount(petServiceAccount: PetServiceAccount): Future[PetServiceAccount]
  def getManagedGroupAccessInstructions(groupName: WorkbenchGroupName): Future[Option[String]]
  def setManagedGroupAccessInstructions(groupName: WorkbenchGroupName, accessInstructions: String): Future[Unit]
  def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId): Future[Unit]
}
