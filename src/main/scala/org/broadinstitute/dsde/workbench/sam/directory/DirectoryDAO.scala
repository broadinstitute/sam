package org.broadinstitute.dsde.workbench.sam.directory

import java.util.Date

import cats.effect.IO
import io.opencensus.trace.Span
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup

/**
  * Created by dvoet on 5/26/17.
  */
trait DirectoryDAO extends RegistrationDAO {
  def createGroup(group: BasicWorkbenchGroup, accessInstructionsOpt: Option[String] = None, parentSpan: Span): IO[BasicWorkbenchGroup]
  def loadGroup(groupName: WorkbenchGroupName, parentSpan: Span): IO[Option[BasicWorkbenchGroup]]
  def loadGroups(groupNames: Set[WorkbenchGroupName], parentSpan: Span): IO[Stream[BasicWorkbenchGroup]]
  def loadGroupEmail(groupName: WorkbenchGroupName, parentSpan: Span): IO[Option[WorkbenchEmail]]
  def batchLoadGroupEmail(groupNames: Set[WorkbenchGroupName], parentSpan: Span): IO[Stream[(WorkbenchGroupName, WorkbenchEmail)]]
  def deleteGroup(groupName: WorkbenchGroupName, parentSpan: Span): IO[Unit]

  /**
    * @return true if the subject was added, false if it was already there
    */
  def addGroupMember(groupId: WorkbenchGroupIdentity, addMember: WorkbenchSubject, parentSpan: Span): IO[Boolean]

  /**
    * @return true if the subject was removed, false if it was already gone
    */
  def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject, parentSpan: Span): IO[Boolean]
  def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject, parentSpan: Span): IO[Boolean]
  def updateSynchronizedDate(groupId: WorkbenchGroupIdentity, parentSpan: Span): IO[Unit]
  def getSynchronizedDate(groupId: WorkbenchGroupIdentity, parentSpan: Span): IO[Option[Date]]
  def getSynchronizedEmail(groupId: WorkbenchGroupIdentity, parentSpan: Span): IO[Option[WorkbenchEmail]]

  def loadSubjectFromEmail(email: WorkbenchEmail, parentSpan: Span): IO[Option[WorkbenchSubject]]
  def loadSubjectEmail(subject: WorkbenchSubject, parentSpan: Span): IO[Option[WorkbenchEmail]]
  def loadSubjectEmails(subjects: Set[WorkbenchSubject], parentSpan: Span): IO[Stream[WorkbenchEmail]]
  def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId, parentSpan: Span): IO[Option[WorkbenchSubject]]

  def createUser(user: WorkbenchUser, parentSpan: Span): IO[WorkbenchUser]
  def loadUser(userId: WorkbenchUserId, parentSpan: Span): IO[Option[WorkbenchUser]]
  def loadUserByIdentityConcentratorId(userId: IdentityConcentratorId, parentSpan: Span): IO[Option[WorkbenchUser]]
  def loadUsers(userIds: Set[WorkbenchUserId], parentSpan: Span): IO[Stream[WorkbenchUser]]
  def deleteUser(userId: WorkbenchUserId, parentSpan: Span): IO[Unit]
  def setUserIdentityConcentratorId(googleSubjectId: GoogleSubjectId, icId: IdentityConcentratorId, parentSpan: Span): IO[Int]


  def listUsersGroups(userId: WorkbenchUserId, parentSpan: Span): IO[Set[WorkbenchGroupIdentity]]
  def listUserDirectMemberships(userId: WorkbenchUserId, parentSpan: Span): IO[Stream[WorkbenchGroupIdentity]]
  def listIntersectionGroupUsers(groupId: Set[WorkbenchGroupIdentity], parentSpan: Span): IO[Set[WorkbenchUserId]]
  def listAncestorGroups(groupId: WorkbenchGroupIdentity, parentSpan: Span): IO[Set[WorkbenchGroupIdentity]]

  def enableIdentity(subject: WorkbenchSubject, parentSpan: Span): IO[Unit]
  def disableIdentity(subject: WorkbenchSubject, parentSpan: Span): IO[Unit]
  def isEnabled(subject: WorkbenchSubject, parentSpan: Span): IO[Boolean]

  def getUserFromPetServiceAccount(petSA: ServiceAccountSubjectId, parentSpan: Span): IO[Option[WorkbenchUser]]
  def createPetServiceAccount(petServiceAccount: PetServiceAccount, parentSpan: Span): IO[PetServiceAccount]
  def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId, parentSpan: Span): IO[Option[PetServiceAccount]]
  def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId, parentSpan: Span): IO[Unit]
  def getAllPetServiceAccountsForUser(userId: WorkbenchUserId, parentSpan: Span): IO[Seq[PetServiceAccount]]
  def updatePetServiceAccount(petServiceAccount: PetServiceAccount, parentSpan: Span): IO[PetServiceAccount]
  def getManagedGroupAccessInstructions(groupName: WorkbenchGroupName, parentSpan: Span): IO[Option[String]]
  def setManagedGroupAccessInstructions(groupName: WorkbenchGroupName, accessInstructions: String, parentSpan: Span): IO[Unit]
  def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId, parentSpan: Span): IO[Unit]
}
