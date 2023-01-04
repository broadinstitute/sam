package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchGroup, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDaoBuilder}
import org.broadinstitute.dsde.workbench.sam.model.SamUser

import scala.collection.mutable
import scala.concurrent.ExecutionContext

case class TestUserServiceBuilder()(implicit
                                    val executionContext: ExecutionContext,
                                    val openTelemetry: OpenTelemetryMetrics[IO]) {

  // TODO - While writing out these "rules" for what describes an "existing" user and a "fully activated" user,
  // it occurred to me that these "definitions" seem like the sort of thing that should be coded into the Business
  // Logic for a SamUser

  // Users that:
  // - "exist" in the DirectoryDao
  // - have neither a GoogleSubjectID nor AzureB2CId
  // - are not enabled
  private val existingUsers: mutable.Set[SamUser] = mutable.Set.empty

  // Users that:
  // - "exist" in the DirectoryDao
  // - have a GoogleSubjectId and/or an AzureB2CId
  // - are enabled
  // - are in the "All_Users" group in Sam
  // - are in the "All_Users" group in Google
  private val fullyActivatedUsers: mutable.Set[SamUser] = mutable.Set.empty

  private var maybeAllUsersGroup: Option[WorkbenchGroup] = None
  private val blockedEmailDomains: mutable.Set[String] = mutable.Set.empty
  private var haveAllAcceptedToS: Boolean = false
  private val tosStatesForUsers: mutable.Map[WorkbenchUserId, Boolean] = mutable.Map.empty

  def withExistingUser(samUser: SamUser): TestUserServiceBuilder = withExistingUsers(List(samUser))
  def withExistingUsers(samUsers: Iterable[SamUser]): TestUserServiceBuilder = {
    existingUsers.addAll(samUsers)
    this
  }

  def withInvitedUser(samUser: SamUser): TestUserServiceBuilder = withExistingUser(samUser)
  def withInvitedUsers(samUsers: Iterable[SamUser]): TestUserServiceBuilder = withFullyActivatedUsers(samUsers)

  def withFullyActivatedUser(samUser: SamUser): TestUserServiceBuilder = withFullyActivatedUsers(List(samUser))
  def withFullyActivatedUsers(samUsers: Iterable[SamUser]): TestUserServiceBuilder = {
    fullyActivatedUsers.addAll(samUsers)
    this
  }

  def withAllUsersGroup(allUsersGroup: WorkbenchGroup): TestUserServiceBuilder = {
    maybeAllUsersGroup = Option(allUsersGroup)
    this
  }

  def withAllUsersHavingAcceptedTos(): TestUserServiceBuilder = {
    haveAllAcceptedToS = true
    this
  }

  def withNoUsersHavingAcceptedTos(): TestUserServiceBuilder = {
    haveAllAcceptedToS = false
    this
  }

  def withToSAcceptanceStateForUser(samUser: SamUser, isAccepted: Boolean): TestUserServiceBuilder = {
    tosStatesForUsers.update(samUser.id, isAccepted)
    this
  }

  def withBlockedEmailDomain(blockedDomain: String): TestUserServiceBuilder = withBlockedEmailDomains(Seq(blockedDomain))
  def withBlockedEmailDomains(blockedDomains: Iterable[String]): TestUserServiceBuilder = {
    blockedEmailDomains.addAll(blockedDomains)
    this
  }

  // This is all a little wonky because the CloudExtensions needs the DirectoryDao to be built first, which is not
  // great, but it is what we need to deal with for now
  def build: UserService = {
    val directoryDAO = buildDirectoryDao()
    val cloudExtensions = buildCloudExtensionsDao(directoryDAO)
    val tosService = buildTosService()

    new UserService(directoryDAO, cloudExtensions, blockedEmailDomains.toSeq, tosService)
  }

  private def buildDirectoryDao(): DirectoryDAO = {
    val mockDirectoryDaoBuilder = MockDirectoryDaoBuilder()

    maybeAllUsersGroup match {
      case Some(g) => mockDirectoryDaoBuilder.withAllUsersGroup(g)
      case None => ()
    }

    mockDirectoryDaoBuilder
      .withExistingUsers(existingUsers)
      .withFullyActivatedUsers(fullyActivatedUsers)
      .build
  }

  private def buildCloudExtensionsDao(directoryDAO: DirectoryDAO): CloudExtensions = {
    val mockCloudExtensionsBuilder = MockCloudExtensionsBuilder(directoryDAO)
    mockCloudExtensionsBuilder
      .withEnabledUsers(fullyActivatedUsers)
      .build
  }

  private def buildTosService(): TosService = {
    val mockTosServiceBuilder = MockTosServiceBuilder()

    // Set the "global default" first
    haveAllAcceptedToS match {
      case true => mockTosServiceBuilder.withAllAccepted()
      case false => mockTosServiceBuilder.withNoneAccepted()
    }

    // Then set any individual ToS states for specific users
    tosStatesForUsers.foreach { tuple: (WorkbenchUserId, Boolean) =>
      mockTosServiceBuilder.withAcceptedStateForUser(tuple._1, tuple._2)
    }

    mockTosServiceBuilder.build
  }
}
