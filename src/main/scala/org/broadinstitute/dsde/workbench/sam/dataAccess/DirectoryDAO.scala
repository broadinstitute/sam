package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.azure.{ManagedIdentityObjectId, PetManagedIdentity, PetManagedIdentityId}
import org.broadinstitute.dsde.workbench.sam.model.api.{SamUser, SamUserAttributes}
import org.broadinstitute.dsde.workbench.sam.model.{ActionServiceAccount, ActionServiceAccountId, BasicWorkbenchGroup, ResourceId, SamUserTos}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import java.time.Instant
import java.util.Date

/** Created by dvoet on 5/26/17.
  */
trait DirectoryDAO {
  def checkStatus(samRequestContext: SamRequestContext): IO[Boolean]
  def createGroup(group: BasicWorkbenchGroup, accessInstructionsOpt: Option[String] = None, samRequestContext: SamRequestContext): IO[BasicWorkbenchGroup]
  def loadGroup(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[BasicWorkbenchGroup]]
  def loadGroupEmail(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]]
  def batchLoadGroupEmail(groupNames: Set[WorkbenchGroupName], samRequestContext: SamRequestContext): IO[LazyList[(WorkbenchGroupName, WorkbenchEmail)]]
  def deleteGroup(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Unit]

  /** @return
    *   true if the subject was added, false if it was already there
    */
  def addGroupMember(groupId: WorkbenchGroupIdentity, addMember: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean]

  /** @return
    *   true if the subject was removed, false if it was already gone
    */
  def removeGroupMember(groupId: WorkbenchGroupIdentity, removeMember: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean]
  def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean]
  def updateSynchronizedDate(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Unit]
  def getSynchronizedDate(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Option[Date]]
  def getSynchronizedEmail(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]]

  def loadSubjectFromEmail(email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Option[WorkbenchSubject]]
  def loadSubjectEmail(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]]
  def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Option[WorkbenchSubject]]

  def createUser(user: SamUser, samRequestContext: SamRequestContext): IO[SamUser]
  def loadUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[SamUser]]
  def loadUsersByQuery(
      userId: Option[WorkbenchUserId],
      googleSubjectId: Option[GoogleSubjectId],
      azureB2CId: Option[AzureB2CId],
      limit: Int,
      samRequestContext: SamRequestContext
  ): IO[Set[SamUser]]
  def loadUserByGoogleSubjectId(userId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Option[SamUser]]
  def loadUserByAzureB2CId(userId: AzureB2CId, samRequestContext: SamRequestContext): IO[Option[SamUser]]
  def setUserAzureB2CId(userId: WorkbenchUserId, b2cId: AzureB2CId, samRequestContext: SamRequestContext): IO[Unit]
  def updateUserEmail(userId: WorkbenchUserId, email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Unit]
  def deleteUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit]

  def listUsersGroups(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]]
  def listUserDirectMemberships(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[LazyList[WorkbenchGroupIdentity]]
  def listIntersectionGroupUsers(groupId: Set[WorkbenchGroupIdentity], samRequestContext: SamRequestContext): IO[Set[WorkbenchUserId]]
  def listAncestorGroups(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]]
  def listFlattenedGroupMembers(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Set[WorkbenchUserId]]

  def enableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit]
  def disableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit]
  def isEnabled(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean]

  def getUserFromPetServiceAccount(petSA: ServiceAccountSubjectId, samRequestContext: SamRequestContext): IO[Option[SamUser]]
  def createPetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount]
  def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Option[PetServiceAccount]]
  def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Unit]
  def getAllPetServiceAccountsForUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Seq[PetServiceAccount]]
  def updatePetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount]
  def createActionServiceAccount(actionServiceAccount: ActionServiceAccount, samRequestContext: SamRequestContext): IO[ActionServiceAccount]
  def loadActionServiceAccount(actionServiceAccountId: ActionServiceAccountId, samRequestContext: SamRequestContext): IO[Option[ActionServiceAccount]]
  def deleteActionServiceAccount(actionServiceAccountId: ActionServiceAccountId, samRequestContext: SamRequestContext): IO[Unit]
  def getAllActionServiceAccountsForResource(
      resourceId: ResourceId,
      googleProject: GoogleProject,
      samRequestContext: SamRequestContext
  ): IO[Seq[ActionServiceAccount]]
  def deleteAllActionServiceAccountsForResource(resourceId: ResourceId, googleProject: GoogleProject, samRequestContext: SamRequestContext): IO[Unit]
  def getManagedGroupAccessInstructions(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[String]]
  def setManagedGroupAccessInstructions(groupName: WorkbenchGroupName, accessInstructions: String, samRequestContext: SamRequestContext): IO[Unit]
  def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Unit]

  def acceptTermsOfService(userId: WorkbenchUserId, tosVersion: String, samRequestContext: SamRequestContext): IO[Boolean]
  def rejectTermsOfService(userId: WorkbenchUserId, tosVersion: String, samRequestContext: SamRequestContext): IO[Boolean]
  def getUserTermsOfService(userId: WorkbenchUserId, samRequestContext: SamRequestContext, action: Option[String] = None): IO[Option[SamUserTos]]
  def getUserTermsOfServiceVersion(
      userId: WorkbenchUserId,
      tosVersion: Option[String],
      samRequestContext: SamRequestContext,
      action: Option[String] = None
  ): IO[Option[SamUserTos]]
  def getUserTermsOfServiceHistory(userId: WorkbenchUserId, samRequestContext: SamRequestContext, limit: Integer): IO[List[SamUserTos]]
  def createPetManagedIdentity(petManagedIdentity: PetManagedIdentity, samRequestContext: SamRequestContext): IO[PetManagedIdentity]
  def loadPetManagedIdentity(petManagedIdentityId: PetManagedIdentityId, samRequestContext: SamRequestContext): IO[Option[PetManagedIdentity]]
  def getUserFromPetManagedIdentity(petManagedIdentityObjectId: ManagedIdentityObjectId, samRequestContext: SamRequestContext): IO[Option[SamUser]]
  def setUserRegisteredAt(userId: WorkbenchUserId, registeredAt: Instant, samRequestContext: SamRequestContext): IO[Unit]
  def getUserAttributes(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[SamUserAttributes]]

  def setUserAttributes(samUserAttributes: SamUserAttributes, samRequestContext: SamRequestContext): IO[Unit]
}
