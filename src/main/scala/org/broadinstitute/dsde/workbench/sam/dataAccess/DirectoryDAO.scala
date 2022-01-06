package org.broadinstitute.dsde.workbench.sam.dataAccess

import java.util.Date

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountSubjectId
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

/**
  * Created by dvoet on 5/26/17.
  */
trait DirectoryDAO extends RegistrationDAO {
  def createGroup(group: BasicWorkbenchGroup, accessInstructionsOpt: Option[String] = None, samRequestContext: SamRequestContext): IO[BasicWorkbenchGroup]
  def loadGroup(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[BasicWorkbenchGroup]]
  def loadGroups(groupNames: Set[WorkbenchGroupName], samRequestContext: SamRequestContext): IO[LazyList[BasicWorkbenchGroup]]
  def loadGroupEmail(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]]
  def batchLoadGroupEmail(groupNames: Set[WorkbenchGroupName], samRequestContext: SamRequestContext): IO[LazyList[(WorkbenchGroupName, WorkbenchEmail)]]
  def deleteGroup(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Unit]

  /**
    * @return true if the subject was added, false if it was already there
    */
  def addGroupMember(groupId: WorkbenchGroupIdentity, addMember: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean]

  /**
    * @return true if the subject was removed, false if it was already gone
    */
  def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean]
  def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean]
  def updateSynchronizedDate(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Unit]
  def getSynchronizedDate(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Option[Date]]
  def getSynchronizedEmail(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]]

  def loadSubjectFromEmail(email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Option[WorkbenchSubject]]
  def loadSubjectEmail(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]]
  def loadSubjectEmails(subjects: Set[WorkbenchSubject], samRequestContext: SamRequestContext): IO[LazyList[WorkbenchEmail]]
  def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Option[WorkbenchSubject]]

  def createUser(user: WorkbenchUser, samRequestContext: SamRequestContext): IO[WorkbenchUser]
  def loadUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[WorkbenchUser]]
  def loadUserByAzureB2CId(userId: AzureB2CId, samRequestContext: SamRequestContext): IO[Option[WorkbenchUser]]
  def loadUsers(userIds: Set[WorkbenchUserId], samRequestContext: SamRequestContext): IO[LazyList[WorkbenchUser]]
  def deleteUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit]
  def setUserAzureB2CId(userId: WorkbenchUserId, b2CId: AzureB2CId, samRequestContext: SamRequestContext): IO[Int]


  def listUsersGroups(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]]
  def listUserDirectMemberships(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[LazyList[WorkbenchGroupIdentity]]
  def listIntersectionGroupUsers(groupId: Set[WorkbenchGroupIdentity], samRequestContext: SamRequestContext): IO[Set[WorkbenchUserId]]
  def listAncestorGroups(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]]
  def listFlattenedGroupMembers(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Set[WorkbenchUserId]]

  def enableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit]
  def disableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit]
  def isEnabled(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean]

  def getUserFromPetServiceAccount(petSA: ServiceAccountSubjectId, samRequestContext: SamRequestContext): IO[Option[WorkbenchUser]]
  def createPetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount]
  def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Option[PetServiceAccount]]
  def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Unit]
  def getAllPetServiceAccountsForUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Seq[PetServiceAccount]]
  def updatePetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount]
  def getManagedGroupAccessInstructions(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[String]]
  def setManagedGroupAccessInstructions(groupName: WorkbenchGroupName, accessInstructions: String, samRequestContext: SamRequestContext): IO[Unit]
  def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Unit]
}
