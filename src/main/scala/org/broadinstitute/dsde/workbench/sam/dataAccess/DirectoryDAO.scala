package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.sam.azure.{
  ActionManagedIdentity,
  ActionManagedIdentityId,
  BillingProfileId,
  ManagedIdentityObjectId,
  PetManagedIdentity,
  PetManagedIdentityId
}
import org.broadinstitute.dsde.workbench.sam.model.api.{AdminUpdateUserRequest, GroupMembershipCount, SamUser, SamUserAttributes}
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, FullyQualifiedResourceId, ResourceAction, ResourceTypeName, SamUserTos}
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

  /** Load up to `limit` synchronized group emails for a given resource type, ordered by email. This includes both the policy-backed groups and (for managed
    * groups) the aggregate group whose name matches the resource id. Used to enumerate existing Google groups for migrations. Pass an `afterEmail` to load only
    * emails strictly after it, which together with `limit` keyset-paginates a migration through the tier.
    */
  def loadSynchronizedGroupEmailsByResourceType(
      resourceTypeName: ResourceTypeName,
      afterEmail: Option[WorkbenchEmail],
      limit: Int,
      samRequestContext: SamRequestContext
  ): IO[Seq[WorkbenchEmail]]

  /** Count the synchronized group emails for a given resource type, matching [[loadSynchronizedGroupEmailsByResourceType]]. Used to record a migration tier's
    * total up front.
    */
  def countSynchronizedGroupEmailsByResourceType(resourceTypeName: ResourceTypeName, samRequestContext: SamRequestContext): IO[Long]

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

  def updateSynchronizedDateAndVersion(group: WorkbenchGroup, samRequestContext: SamRequestContext): IO[Unit]

  def updateGroupUpdatedDateAndVersionWithSession(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Unit]

  def getSynchronizedDate(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Option[Date]]

  def getSynchronizedEmail(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]]

  def loadSubjectFromEmail(email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Option[WorkbenchSubject]]

  def loadSubjectEmail(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Option[WorkbenchEmail]]

  def loadSubjectFromGoogleSubjectId(googleSubjectId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Option[WorkbenchSubject]]

  def createUser(user: SamUser, samRequestContext: SamRequestContext): IO[SamUser]

  def loadUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[SamUser]]

  def batchLoadUsers(
      samUserIds: Set[WorkbenchUserId],
      samRequestContext: SamRequestContext
  ): IO[Seq[SamUser]]

  def loadUsersByQuery(
      userId: Option[WorkbenchUserId],
      googleSubjectId: Option[GoogleSubjectId],
      azureB2CId: Option[AzureB2CId],
      limit: Int,
      samRequestContext: SamRequestContext
  ): IO[Set[SamUser]]

  /** Load up to `limit` enabled users ordered by id. Pass an `afterUserId` to load only users strictly after it, which together with `limit` keyset-paginates a
    * migration through the enabled users. Used to enumerate users whose proxy groups need to be migrated.
    */
  def loadEnabledUsers(afterUserId: Option[WorkbenchUserId], limit: Int, samRequestContext: SamRequestContext): IO[Seq[SamUser]]

  /** Count the enabled users, matching [[loadEnabledUsers]]. Used to record the proxy migration tier's total up front. */
  def countEnabledUsers(samRequestContext: SamRequestContext): IO[Long]

  /** Record the mutable progress of a tier: its state, total, processed/failed counts, and resume cursor, bumping the heartbeat. Used to mark a tier `running`
    * at the start of a run, for periodic heartbeats, and for the terminal `completed` state.
    */
  def recordExternalMembersMigration(
      tier: String,
      state: String,
      total: Option[Long],
      processed: Long,
      failed: Long,
      lastCursor: Option[String],
      samRequestContext: SamRequestContext
  ): IO[Unit]

  /** Set only the state (and heartbeat) of a tier, preserving its progress columns. Used to mark a tier `failed` without clobbering the last recorded counts.
    */
  def setExternalMembersMigrationState(tier: String, state: String, samRequestContext: SamRequestContext): IO[Unit]

  def listExternalMembersMigrations(samRequestContext: SamRequestContext): IO[Seq[ExternalMembersMigrationRecord]]

  def getExternalMembersMigration(tier: String, samRequestContext: SamRequestContext): IO[Option[ExternalMembersMigrationRecord]]

  /** Load up to `limit` users whose email matches the given SQL `LIKE` pattern, ordered by id. Pass an `afterUserId` to load only users strictly after it,
    * which together with `limit` keyset-paginates a cleanup run through the matches. A pattern with no `%`/`_` wildcards behaves as an exact-match lookup.
    */
  def loadUsersByEmailPattern(pattern: String, afterUserId: Option[WorkbenchUserId], limit: Int, samRequestContext: SamRequestContext): IO[Seq[SamUser]]

  /** Count the users matching [[loadUsersByEmailPattern]]'s pattern. Used for the preview endpoint and to record a cleanup run's total up front. */
  def countUsersByEmailPattern(pattern: String, samRequestContext: SamRequestContext): IO[Long]

  /** Record the mutable progress of an All_Users cleanup run for one email pattern: its state, total, processed/failed counts, and resume cursor, bumping the
    * heartbeat. Used to mark a run `running` at the start, for periodic heartbeats, and for the terminal `completed` state.
    */
  def recordAllUsersCleanupProgress(
      emailPattern: String,
      state: String,
      total: Option[Long],
      processed: Long,
      failed: Long,
      lastCursor: Option[String],
      samRequestContext: SamRequestContext
  ): IO[Unit]

  /** Set only the state (and heartbeat) of a cleanup run, preserving its progress columns. Used to mark a run `failed` without clobbering the last recorded
    * counts.
    */
  def setAllUsersCleanupState(emailPattern: String, state: String, samRequestContext: SamRequestContext): IO[Unit]

  def listAllUsersCleanupRuns(samRequestContext: SamRequestContext): IO[Seq[AllUsersCleanupRecord]]

  def getAllUsersCleanupRun(emailPattern: String, samRequestContext: SamRequestContext): IO[Option[AllUsersCleanupRecord]]

  def loadUserByGoogleSubjectId(userId: GoogleSubjectId, samRequestContext: SamRequestContext): IO[Option[SamUser]]

  def loadUserByAzureB2CId(userId: AzureB2CId, samRequestContext: SamRequestContext): IO[Option[SamUser]]

  def setUserAzureB2CId(userId: WorkbenchUserId, b2cId: AzureB2CId, samRequestContext: SamRequestContext): IO[Unit]

  def loadUserByEmail(email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Option[SamUser]]

  def updateUserEmail(userId: WorkbenchUserId, email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Unit]

  def deleteUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Unit]

  def updateUser(samUser: SamUser, userUpdate: AdminUpdateUserRequest, samRequestContext: SamRequestContext): IO[Option[SamUser]]

  def listUsersGroups(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]]

  def listUserDirectMemberships(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[LazyList[WorkbenchGroupIdentity]]

  def listIntersectionGroupUsers(groupId: Set[WorkbenchGroupIdentity], samRequestContext: SamRequestContext): IO[Set[WorkbenchUserId]]

  def listAncestorGroups(groupId: WorkbenchGroupIdentity, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupIdentity]]

  def listFlattenedGroupMembers(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Set[WorkbenchUserId]]

  /** @return
    *   the number of groups to which the user is a direct member, not including public resources.
    */
  def countDirectSynchronizedGroupMemberships(samUser: SamUser, samRequestContext: SamRequestContext): IO[Int]

  /** @return
    *   the number of groups to which the user is a direct or indirect member, not including public resources.
    */
  def countIndirectSynchronizedGroupMemberships(samUser: SamUser, samRequestContext: SamRequestContext): IO[Int]

  /** @return
    *   the number of groups to which the user is an indirect member via the All_Users group on public resources.
    */
  def countIndirectPublicGroupMemberships(samUser: SamUser, samRequestContext: SamRequestContext): IO[Int]

  def enableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit]

  def disableIdentity(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Unit]

  def isEnabled(subject: WorkbenchSubject, samRequestContext: SamRequestContext): IO[Boolean]

  def getUserFromPetServiceAccount(petSA: ServiceAccountSubjectId, samRequestContext: SamRequestContext): IO[Option[SamUser]]

  def createPetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount]

  def loadPetServiceAccount(petServiceAccountId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Option[PetServiceAccount]]

  def deletePetServiceAccount(petServiceAccountId: PetServiceAccountId, samRequestContext: SamRequestContext): IO[Unit]

  def getAllPetServiceAccountsForUser(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Seq[PetServiceAccount]]

  def getAllPetServiceAccountsForProject(project: GoogleProject, samRequestContext: SamRequestContext): IO[Seq[PetServiceAccount]]

  def updatePetServiceAccount(petServiceAccount: PetServiceAccount, samRequestContext: SamRequestContext): IO[PetServiceAccount]

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

  def createActionManagedIdentity(actionManagedIdentity: ActionManagedIdentity, samRequestContext: SamRequestContext): IO[ActionManagedIdentity]

  def loadActionManagedIdentity(actionManagedIdentityId: ActionManagedIdentityId, samRequestContext: SamRequestContext): IO[Option[ActionManagedIdentity]]

  def loadActionManagedIdentity(
      resource: FullyQualifiedResourceId,
      action: ResourceAction,
      samRequestContext: SamRequestContext
  ): IO[Option[ActionManagedIdentity]]

  def updateActionManagedIdentity(actionManagedIdentity: ActionManagedIdentity, samRequestContext: SamRequestContext): IO[ActionManagedIdentity]

  def deleteActionManagedIdentity(actionManagedIdentityId: ActionManagedIdentityId, samRequestContext: SamRequestContext): IO[Unit]

  def getAllActionManagedIdentitiesForResource(
      resourceId: FullyQualifiedResourceId,
      samRequestContext: SamRequestContext
  ): IO[Seq[ActionManagedIdentity]]

  def deleteAllActionManagedIdentitiesForResource(resourceId: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit]

  def getAllActionManagedIdentitiesForBillingProfile(
      billingProfileId: BillingProfileId,
      samRequestContext: SamRequestContext
  ): IO[Seq[ActionManagedIdentity]]

  def deleteAllActionManagedIdentitiesForBillingProfile(billingProfileId: BillingProfileId, samRequestContext: SamRequestContext): IO[Unit]

  def setUserRegisteredAt(userId: WorkbenchUserId, registeredAt: Instant, samRequestContext: SamRequestContext): IO[Unit]

  def getUserAttributes(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Option[SamUserAttributes]]

  def setUserAttributes(samUserAttributes: SamUserAttributes, samRequestContext: SamRequestContext): IO[Unit]

  def listParentGroups(groupName: WorkbenchGroupName, samRequestContext: SamRequestContext): IO[Set[WorkbenchGroupName]]

  def addUserFavoriteResource(userId: WorkbenchUserId, resourceId: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Boolean]

  def removeUserFavoriteResource(userId: WorkbenchUserId, resourceId: FullyQualifiedResourceId, samRequestContext: SamRequestContext): IO[Unit]

  def getUserFavoriteResources(userId: WorkbenchUserId, samRequestContext: SamRequestContext): IO[Set[FullyQualifiedResourceId]]

  def getUserFavoriteResourcesOfType(
      userId: WorkbenchUserId,
      resourceTypeName: ResourceTypeName,
      samRequestContext: SamRequestContext
  ): IO[Set[FullyQualifiedResourceId]]

  /** List the top groups contributing to the most memberships for a user.
    * @param samUser
    *   the user to list groups for
    * @param limit
    *   the maximum number of groups to return
    * @param samRequestContext
    * @return
    *   a map of group to membership count
    */
  def listGroupsContributingToMostMemberships(samUser: SamUser, limit: Int, samRequestContext: SamRequestContext): IO[List[GroupMembershipCount]]
}
