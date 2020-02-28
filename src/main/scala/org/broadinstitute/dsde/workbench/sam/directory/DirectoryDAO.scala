package org.broadinstitute.dsde.workbench.sam.directory

import java.util.Date

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup

/**
  * Created by dvoet on 5/26/17.
  */
trait DirectoryDAO extends RegistrationDAO {
  def createGroup(group: BasicWorkbenchGroup, accessInstructionsOpt: Option[String] = None): IO[BasicWorkbenchGroup]
  def loadGroup(groupName: WorkbenchGroupName): IO[Option[BasicWorkbenchGroup]]
  def loadGroups(groupNames: Set[WorkbenchGroupName]): IO[Stream[BasicWorkbenchGroup]]
  def loadGroupEmail(groupName: WorkbenchGroupName): IO[Option[WorkbenchEmail]]
  def batchLoadGroupEmail(groupNames: Set[WorkbenchGroupName]): IO[Stream[(WorkbenchGroupName, WorkbenchEmail)]]
  def deleteGroup(groupName: WorkbenchGroupName): IO[Unit]

  /**
    * @return true if the subject was added, false if it was already there
    */
  def addGroupMember(groupId: WorkbenchGroupIdentity, addMember: WorkbenchSubject): IO[Boolean]

  /**
    * @return true if the subject was removed, false if it was already gone
    */
  def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject): IO[Boolean]
  def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject): IO[Boolean]
  def updateSynchronizedDate(groupId: WorkbenchGroupIdentity): IO[Unit]
  def getSynchronizedDate(groupId: WorkbenchGroupIdentity): IO[Option[Date]]
  def getSynchronizedEmail(groupId: WorkbenchGroupIdentity): IO[Option[WorkbenchEmail]]

  def loadSubjectFromEmail(email: WorkbenchEmail): IO[Option[WorkbenchSubject]]
  def loadSubjectEmail(subject: WorkbenchSubject): IO[Option[WorkbenchEmail]]
  def loadSubjectEmails(subjects: Set[WorkbenchSubject]): IO[Stream[WorkbenchEmail]]
  def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId): IO[Option[WorkbenchSubject]]

  def createUser(user: WorkbenchUser): IO[WorkbenchUser]
  def loadUser(userId: WorkbenchUserId): IO[Option[WorkbenchUser]]
  def loadUserByIdentityConcentratorId(userId: IdentityConcentratorId): IO[Option[WorkbenchUser]]
  def loadUsers(userIds: Set[WorkbenchUserId]): IO[Stream[WorkbenchUser]]
  def deleteUser(userId: WorkbenchUserId): IO[Unit]

  def listUsersGroups(userId: WorkbenchUserId): IO[Set[WorkbenchGroupIdentity]]
  def listUserDirectMemberships(userId: WorkbenchUserId): IO[Stream[WorkbenchGroupIdentity]]
  def listIntersectionGroupUsers(groupId: Set[WorkbenchGroupIdentity]): IO[Set[WorkbenchUserId]]
  def listAncestorGroups(groupId: WorkbenchGroupIdentity): IO[Set[WorkbenchGroupIdentity]]

  def enableIdentity(subject: WorkbenchSubject): IO[Unit]
  def disableIdentity(subject: WorkbenchSubject): IO[Unit]
  def isEnabled(subject: WorkbenchSubject): IO[Boolean]

  def getUserFromPetServiceAccount(petSA: ServiceAccountSubjectId): IO[Option[WorkbenchUser]]
  def createPetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount]
  def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId): IO[Option[PetServiceAccount]]
  def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId): IO[Unit]
  def getAllPetServiceAccountsForUser(userId: WorkbenchUserId): IO[Seq[PetServiceAccount]]
  def updatePetServiceAccount(petServiceAccount: PetServiceAccount): IO[PetServiceAccount]
  def getManagedGroupAccessInstructions(groupName: WorkbenchGroupName): IO[Option[String]]
  def setManagedGroupAccessInstructions(groupName: WorkbenchGroupName, accessInstructions: String): IO[Unit]
  def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId): IO[Unit]
}
