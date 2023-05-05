package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.WorkbenchGroup
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, StatefulMockDirectoryDaoBuilder}
import org.broadinstitute.dsde.workbench.sam.model.SamUser

import scala.collection.mutable
import scala.concurrent.ExecutionContext

case class TestUserServiceBuilder()(implicit val executionContext: ExecutionContext, val openTelemetry: OpenTelemetryMetrics[IO]) {

  // TODO - While writing out these "rules" for what describes an "existing" user and a "fully activated" user,
  // it occurred to me that these "definitions" seem like the sort of thing that should be coded into the Business
  // Logic for a SamUser

  // Users that:
  // - "exist" in the DirectoryDao
  // - have neither a GoogleSubjectID nor AzureB2CId
  // - are not enabled
  private val existingUsers: mutable.Set[SamUser] = mutable.Set.empty

  // What defines an enabled user:
  // - "exist" in the DirectoryDao
  // - have a GoogleSubjectId and/or an AzureB2CId
  // - are in the "All_Users" group in Sam
  // - are in the "All_Users" group in Google
  private val enabledUsers: mutable.Set[SamUser] = mutable.Set.empty
  private val disabledUsers: mutable.Set[SamUser] = mutable.Set.empty

  private var maybeAllUsersGroup: Option[WorkbenchGroup] = None
  private var maybeWorkbenchGroup: Option[WorkbenchGroup] = None
  private val blockedEmailDomains: mutable.Set[String] = mutable.Set.empty
  private var haveAllAcceptedToS: Boolean = false
  private val tosStatesForUsers: mutable.Map[SamUser, Boolean] = mutable.Map.empty

  def withExistingUser(samUser: SamUser): TestUserServiceBuilder = withExistingUsers(List(samUser))
  def withExistingUsers(samUsers: Iterable[SamUser]): TestUserServiceBuilder = {
    existingUsers.addAll(samUsers)
    this
  }

  def withInvitedUser(samUser: SamUser): TestUserServiceBuilder = withExistingUser(samUser)
  def withInvitedUsers(samUsers: Iterable[SamUser]): TestUserServiceBuilder = withExistingUsers(samUsers)

  def withEnabledUser(samUser: SamUser): TestUserServiceBuilder = withEnabledUsers(List(samUser))
  def withEnabledUsers(samUsers: Iterable[SamUser]): TestUserServiceBuilder = {
    enabledUsers.addAll(samUsers)
    this
  }

  // A disabled user is explicitly a user who was previously enabled and is now no longer enabled
  def withDisabledUser(samUser: SamUser): TestUserServiceBuilder = withDisabledUsers(List(samUser))

  def withDisabledUsers(samUsers: Iterable[SamUser]): TestUserServiceBuilder = {
    disabledUsers.addAll(samUsers)
    this
  }

  def withAllUsersGroup(allUsersGroup: WorkbenchGroup): TestUserServiceBuilder = {
    maybeAllUsersGroup = Option(allUsersGroup)
    this
  }

  def withWorkbenchGroup(workbenchGroup: WorkbenchGroup): TestUserServiceBuilder = {
    maybeWorkbenchGroup = Option(workbenchGroup)
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
    tosStatesForUsers.update(samUser, isAccepted)
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
    val mockDirectoryDaoBuilder = StatefulMockDirectoryDaoBuilder()

    maybeAllUsersGroup match {
      case Some(g) => mockDirectoryDaoBuilder.withAllUsersGroup(g)
      case None => ()
    }

    maybeWorkbenchGroup match {
      case Some(g) => mockDirectoryDaoBuilder.withWorkbenchGroup(g)
      case _ => ()
    }

    mockDirectoryDaoBuilder
      .withExistingUsers(existingUsers)
      .withEnabledUsers(enabledUsers)
      .withDisabledUsers(disabledUsers)
      .build
  }

  private def buildCloudExtensionsDao(directoryDAO: DirectoryDAO): CloudExtensions = {
    val mockCloudExtensionsBuilder = StatefulMockCloudExtensionsBuilder(directoryDAO)
    mockCloudExtensionsBuilder
      .withEnabledUsers(enabledUsers)
      .withDisabledUsers(disabledUsers)
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
    tosStatesForUsers.foreach { tuple: (SamUser, Boolean) =>
      mockTosServiceBuilder.withAcceptedStateForUser(tuple._1, tuple._2)
    }

    mockTosServiceBuilder.build
  }
}
