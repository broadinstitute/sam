package org.broadinstitute.dsde.workbench.sam.directory

import java.util.Date

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.util.TraceContext

/**
  * Created by dvoet on 5/26/17.
  */
trait DirectoryDAO extends RegistrationDAO {
  def createGroup(group: BasicWorkbenchGroup, accessInstructionsOpt: Option[String] = None, traceContext: TraceContext): IO[BasicWorkbenchGroup]
  def loadGroup(groupName: WorkbenchGroupName, traceContext: TraceContext): IO[Option[BasicWorkbenchGroup]]
  def loadGroups(groupNames: Set[WorkbenchGroupName], traceContext: TraceContext): IO[Stream[BasicWorkbenchGroup]]
  def loadGroupEmail(groupName: WorkbenchGroupName, traceContext: TraceContext): IO[Option[WorkbenchEmail]]
  def batchLoadGroupEmail(groupNames: Set[WorkbenchGroupName], traceContext: TraceContext): IO[Stream[(WorkbenchGroupName, WorkbenchEmail)]]
  def deleteGroup(groupName: WorkbenchGroupName, traceContext: TraceContext): IO[Unit]

  /**
    * @return true if the subject was added, false if it was already there
    */
  def addGroupMember(groupId: WorkbenchGroupIdentity, addMember: WorkbenchSubject, traceContext: TraceContext): IO[Boolean]

  /**
    * @return true if the subject was removed, false if it was already gone
    */
  def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject, traceContext: TraceContext): IO[Boolean]
  def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject, traceContext: TraceContext): IO[Boolean]
  def updateSynchronizedDate(groupId: WorkbenchGroupIdentity, traceContext: TraceContext): IO[Unit]
  def getSynchronizedDate(groupId: WorkbenchGroupIdentity, traceContext: TraceContext): IO[Option[Date]]
  def getSynchronizedEmail(groupId: WorkbenchGroupIdentity, traceContext: TraceContext): IO[Option[WorkbenchEmail]]

  def loadSubjectFromEmail(email: WorkbenchEmail, traceContext: TraceContext): IO[Option[WorkbenchSubject]]
  def loadSubjectEmail(subject: WorkbenchSubject, traceContext: TraceContext): IO[Option[WorkbenchEmail]]
  def loadSubjectEmails(subjects: Set[WorkbenchSubject], traceContext: TraceContext): IO[Stream[WorkbenchEmail]]
  def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId, traceContext: TraceContext): IO[Option[WorkbenchSubject]]

  def createUser(user: WorkbenchUser, traceContext: TraceContext): IO[WorkbenchUser]
  def loadUser(userId: WorkbenchUserId, traceContext: TraceContext): IO[Option[WorkbenchUser]]
  def loadUserByIdentityConcentratorId(userId: IdentityConcentratorId, traceContext: TraceContext): IO[Option[WorkbenchUser]]
  def loadUsers(userIds: Set[WorkbenchUserId], traceContext: TraceContext): IO[Stream[WorkbenchUser]]
  def deleteUser(userId: WorkbenchUserId, traceContext: TraceContext): IO[Unit]
  def setUserIdentityConcentratorId(googleSubjectId: GoogleSubjectId, icId: IdentityConcentratorId, traceContext: TraceContext): IO[Int]


  def listUsersGroups(userId: WorkbenchUserId, traceContext: TraceContext): IO[Set[WorkbenchGroupIdentity]]
  def listUserDirectMemberships(userId: WorkbenchUserId, traceContext: TraceContext): IO[Stream[WorkbenchGroupIdentity]]
  def listIntersectionGroupUsers(groupId: Set[WorkbenchGroupIdentity], traceContext: TraceContext): IO[Set[WorkbenchUserId]]
  def listAncestorGroups(groupId: WorkbenchGroupIdentity, traceContext: TraceContext): IO[Set[WorkbenchGroupIdentity]]

  def enableIdentity(subject: WorkbenchSubject, traceContext: TraceContext): IO[Unit]
  def disableIdentity(subject: WorkbenchSubject, traceContext: TraceContext): IO[Unit]
  def isEnabled(subject: WorkbenchSubject, traceContext: TraceContext): IO[Boolean]

  def getUserFromPetServiceAccount(petSA: ServiceAccountSubjectId, traceContext: TraceContext): IO[Option[WorkbenchUser]]
  def createPetServiceAccount(petServiceAccount: PetServiceAccount, traceContext: TraceContext): IO[PetServiceAccount]
  def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId, traceContext: TraceContext): IO[Option[PetServiceAccount]]
  def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId, traceContext: TraceContext): IO[Unit]
  def getAllPetServiceAccountsForUser(userId: WorkbenchUserId, traceContext: TraceContext): IO[Seq[PetServiceAccount]]
  def updatePetServiceAccount(petServiceAccount: PetServiceAccount, traceContext: TraceContext): IO[PetServiceAccount]
  def getManagedGroupAccessInstructions(groupName: WorkbenchGroupName, traceContext: TraceContext): IO[Option[String]]
  def setManagedGroupAccessInstructions(groupName: WorkbenchGroupName, accessInstructions: String, traceContext: TraceContext): IO[Unit]
  def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId, traceContext: TraceContext): IO[Unit]
}
