package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.ServiceAccountSubjectId
import org.broadinstitute.dsde.workbench.sam.azure.{ManagedIdentityObjectId, PetManagedIdentity, PetManagedIdentityId}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import java.util.Date
import scala.concurrent.ExecutionContext

class MockUserService(
    directoryDAO: DirectoryDAO = new MockDirectoryDAO(),
    cloudExtensions: CloudExtensions = null,
    blockedEmailDomains: Seq[String] = Seq(),
    tosService: TosService = null
)(implicit executionContext: ExecutionContext)
    extends UserService(directoryDAO, cloudExtensions, blockedEmailDomains, tosService) {

  def createGroup(
      group: BasicWorkbenchGroup,
      accessInstruction: Option[String] = None,
      samRequestContext: SamRequestContext
  ): IO[BasicWorkbenchGroup] =
    directoryDAO.createGroup(group, accessInstruction, samRequestContext)

  def loadGroup(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[BasicWorkbenchGroup]] =
    directoryDAO.loadGroup(groupName, samRequestContext)

  def deleteGroup(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Unit] =
    directoryDAO.deleteGroup(groupName, samRequestContext)

  def addGroupMember(groupName: WorkbenchGroupIdentity, addMember: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDAO.addGroupMember(groupName, addMember, samRequestContext)

  def removeGroupMember(groupName: WorkbenchGroupIdentity, removeMember: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDAO.removeGroupMember(groupName, removeMember, samRequestContext)

  def isGroupMember(groupId: WorkbenchGroupIdentity, member: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDAO.isGroupMember(groupId, member, samRequestContext)

  def loadSubjectFromEmail(email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Option[WorkbenchSubject]] =
    directoryDAO.loadSubjectFromEmail(email, samRequestContext)

  def createUserDAO(user: SamUser, samRequestContext: SamRequestContext): IO[SamUser] =
    directoryDAO.createUser(user, samRequestContext)

  def loadUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    directoryDAO.loadUser(userId, samRequestContext)

  def deleteUserDAO(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit] =
    directoryDAO.deleteUser(userId, samRequestContext)

  def listUsersGroups(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]] =
    directoryDAO.listUsersGroups(userId, samRequestContext)

  def listIntersectionGroupUsers(groupIds: Set[WorkbenchGroupIdentity], samRequestContext: SamRequestContext): IO[Set[WorkbenchUserId]] =
    directoryDAO.listIntersectionGroupUsers(groupIds, samRequestContext)

  def listAncestorGroups(groupName: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]] =
    directoryDAO.listAncestorGroups(groupName, samRequestContext)

  def enableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit] =
    directoryDAO.enableIdentity(subject, samRequestContext)

  def disableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit] =
    directoryDAO.disableIdentity(subject, samRequestContext)

  def isEnabled(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDAO.isEnabled(subject, samRequestContext)

  def loadGroupEmail(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]] =
    directoryDAO.loadGroupEmail(groupName, samRequestContext)

  def batchLoadGroupEmail(
      groupNames: Set[WorkbenchGroupName],
      samRequestContext: SamRequestContext
  ): IO[LazyList[(WorkbenchGroupName, WorkbenchEmail)]] =
    directoryDAO.batchLoadGroupEmail(groupNames, samRequestContext)

  def createPetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount] =
    directoryDAO.createPetServiceAccount(petServiceAccount, samRequestContext)

  def loadPetServiceAccount(petServiceAccountUniqueId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Option[PetServiceAccount]] =
    directoryDAO.loadPetServiceAccount(petServiceAccountUniqueId, samRequestContext)

  def deletePetServiceAccount(petServiceAccountUniqueId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Unit] =
    directoryDAO.deletePetServiceAccount(petServiceAccountUniqueId, samRequestContext)

  def getAllPetServiceAccountsForUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Seq[PetServiceAccount]] =
    directoryDAO.getAllPetServiceAccountsForUser(userId, samRequestContext)

  def updateSynchronizedDateAndVersion(group: WorkbenchGroup, samRequestContext: SamRequestContext): IO[Unit] =
    directoryDAO.updateSynchronizedDateAndVersion(group, samRequestContext)

  def loadSubjectEmail(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]] =
    directoryDAO.loadSubjectEmail(subject, samRequestContext)

  def getSynchronizedDate(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Option[Date]] =
    directoryDAO.getSynchronizedDate(groupId, samRequestContext)

  def getSynchronizedEmail(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]] =
    directoryDAO.getSynchronizedEmail(groupId, samRequestContext)

  def getUserFromPetServiceAccountDAO(petSAId: ServiceAccountSubjectId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    directoryDAO.getUserFromPetServiceAccount(petSAId, samRequestContext)

  def updatePetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount] =
    directoryDAO.updatePetServiceAccount(petServiceAccount, samRequestContext)

  def getManagedGroupAccessInstructions(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Option[String]] =
    directoryDAO.getManagedGroupAccessInstructions(groupName, samRequestContext)

  def setManagedGroupAccessInstructions(groupName: WorkbenchGroupName, accessInstructions: String, samRequestContext: SamRequestContext): IO[Unit] =
    directoryDAO.setManagedGroupAccessInstructions(groupName, accessInstructions, samRequestContext)

  def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Option[WorkbenchSubject]] =
    directoryDAO.loadSubjectFromGoogleSubjectId(googleSubjectId, samRequestContext)

  def setGoogleSubjectId(userId: WorkbenchUserId, googleSubjectId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Unit] =
    directoryDAO.setGoogleSubjectId(userId, googleSubjectId, samRequestContext)

  def listUserDirectMemberships(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[LazyList[WorkbenchGroupIdentity]] =
    directoryDAO.listUserDirectMemberships(userId, samRequestContext)

  def listFlattenedGroupMembers(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Set[WorkbenchUserId]] =
    directoryDAO.listFlattenedGroupMembers(groupName, samRequestContext)

  def loadUserByAzureB2CId(userId: AzureB2CId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    directoryDAO.loadUserByAzureB2CId(userId, samRequestContext)

  def setUserAzureB2CIdDAO(userId: WorkbenchUserId, b2CId: AzureB2CId, samRequestContext: SamRequestContext): IO[Unit] =
    directoryDAO.setUserAzureB2CId(userId, b2CId, samRequestContext);

  def checkStatus(samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDAO.checkStatus(samRequestContext)

  def loadUserByGoogleSubjectId(userId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    directoryDAO.loadUserByGoogleSubjectId(userId, samRequestContext)

  def acceptTermsOfServiceDAO(userId: WorkbenchUserId, tosVersion: String, samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDAO.acceptTermsOfService(userId, tosVersion, samRequestContext)

  def rejectTermsOfServiceDAO(userId: WorkbenchUserId, tosVersion: String, samRequestContext: SamRequestContext): IO[Boolean] =
    directoryDAO.rejectTermsOfService(userId, tosVersion, samRequestContext)

  def createPetManagedIdentity(petManagedIdentity: PetManagedIdentity, samRequestContext: SamRequestContext): IO[PetManagedIdentity] =
    directoryDAO.createPetManagedIdentity(petManagedIdentity, samRequestContext)

  def loadPetManagedIdentity(petManagedIdentityId: PetManagedIdentityId, samRequestContext: SamRequestContext): IO[Option[PetManagedIdentity]] =
    directoryDAO.loadPetManagedIdentity(petManagedIdentityId, samRequestContext)

  def getUserFromPetManagedIdentityDAO(petManagedIdentityObjectId: ManagedIdentityObjectId, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    directoryDAO.getUserFromPetManagedIdentity(petManagedIdentityObjectId, samRequestContext)
}
