package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.google.GoogleProject
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroup, WorkbenchGroupIdentity, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.util.health.{SubsystemStatus, Subsystems}
import org.mockito.ArgumentMatchersSugar.{any, argThat, eqTo}
import org.mockito.{IdiomaticMockito, Strictness}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

case class MockCloudExtensionsBuilder(allUsersGroup: WorkbenchGroup) extends IdiomaticMockito {
  var maybeAllUsersGroup: Option[WorkbenchGroup] = None
  val mockedCloudExtensions: CloudExtensions = mock[CloudExtensions](withSettings.strictness(Strictness.Lenient))

  mockedCloudExtensions.getOrCreateAllUsersGroup(any[DirectoryDAO], any[SamRequestContext])(any[ExecutionContext]) returns IO(allUsersGroup)
  mockedCloudExtensions.onUserCreate(any[SamUser], any[SamRequestContext]) returns IO.unit
  mockedCloudExtensions.getUserStatus(any[SamUser]) returns IO(false)
  mockedCloudExtensions.onUserEnable(any[SamUser], any[SamRequestContext]) returns IO.unit
  mockedCloudExtensions.onUserDisable(any[SamUser], any[SamRequestContext]) returns IO.unit
  mockedCloudExtensions.onGroupUpdate(any[Seq[WorkbenchGroupIdentity]], any[SamRequestContext]) returns IO.unit
  mockedCloudExtensions.onUserDelete(any[WorkbenchUserId], any[SamRequestContext]) returns IO.unit
  mockedCloudExtensions.allSubSystems returns Set.empty
  mockedCloudExtensions.checkStatus returns Map.empty
  mockedCloudExtensions.deleteUserPetServiceAccount(any[WorkbenchUserId], any[GoogleProject], any[SamRequestContext]) returns IO(true)

  def withEnabledUser(samUser: SamUser): MockCloudExtensionsBuilder = withEnabledUsers(Set(samUser))
  def withEnabledUsers(samUsers: Iterable[SamUser]): MockCloudExtensionsBuilder = {
    samUsers.foreach(makeUserAppearEnabled)
    this
  }

  def withDisabledUser(samUser: SamUser): MockCloudExtensionsBuilder = withDisabledUsers(Set(samUser))
  def withDisabledUsers(samUsers: Iterable[SamUser]): MockCloudExtensionsBuilder = {
    samUsers.foreach(makeUserAppearDisabled)
    this
  }

  def withAdminUser(samUser: SamUser): MockCloudExtensionsBuilder = withAdminUser(samUser.email)
  def withAdminUser(adminUserEmail: WorkbenchEmail): MockCloudExtensionsBuilder = {
    mockedCloudExtensions.isWorkbenchAdmin(eqTo(adminUserEmail)) returns Future.successful(true)
    this
  }
  // testing cases where nonAdmin is attempting to use admin routes
  def withNonAdminUser(samUser: SamUser): MockCloudExtensionsBuilder = withNonAdminUser(samUser.email)
  def withNonAdminUser(adminUserEmail: WorkbenchEmail): MockCloudExtensionsBuilder = {
    mockedCloudExtensions.isWorkbenchAdmin(eqTo(adminUserEmail)) returns Future.successful(false)
    this
  }

  def withAdminUsers(samUsers: Iterable[SamUser]): MockCloudExtensionsBuilder = {
    samUsers.foreach(u => withAdminUser(u))
    this
  }

  private val subsystemStatuses: mutable.Map[Subsystems.Subsystem, Future[SubsystemStatus]] = mutable.Map.empty

  def withHealthySubsystem(subsystem: Subsystems.Subsystem): MockCloudExtensionsBuilder = {
    subsystemStatuses.addOne(subsystem -> Future.successful(SubsystemStatus(true, None)))
    this
  }

  def withUnhealthySubsystem(subsystem: Subsystems.Subsystem, messages: List[String]): MockCloudExtensionsBuilder = {
    subsystemStatuses.addOne(subsystem -> Future.successful(SubsystemStatus(false, Some(messages))))
    this
  }

  private def makeUserAppearEnabled(samUser: SamUser): Unit =
    mockedCloudExtensions.getUserStatus(argThat(IsSameUserAs(samUser))) returns IO(true)

  private def makeUserAppearDisabled(samUser: SamUser): Unit =
    mockedCloudExtensions.getUserStatus(argThat(IsSameUserAs(samUser))) returns IO(false)

  def build: CloudExtensions = {
    mockedCloudExtensions.checkStatus returns subsystemStatuses.toMap
    mockedCloudExtensions
  }
}
