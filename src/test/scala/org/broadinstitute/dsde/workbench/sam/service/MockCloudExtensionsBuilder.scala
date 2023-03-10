package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchGroup, WorkbenchGroupIdentity, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchersSugar.{any, argThat}
import org.mockito.IdiomaticMockito
import org.mockito.Mockito.RETURNS_SMART_NULLS

import scala.concurrent.{ExecutionContext, Future}

case class MockCloudExtensionsBuilder(allUsersGroup: WorkbenchGroup) extends IdiomaticMockito {
  var maybeAllUsersGroup: Option[WorkbenchGroup] = None
  val mockedCloudExtensions: CloudExtensions = mock[CloudExtensions](RETURNS_SMART_NULLS)

  mockedCloudExtensions.getOrCreateAllUsersGroup(any[DirectoryDAO], any[SamRequestContext])(any[ExecutionContext]) returns IO(allUsersGroup)
  mockedCloudExtensions.onUserCreate(any[SamUser], any[SamRequestContext]) returns IO.unit
  mockedCloudExtensions.getUserStatus(any[SamUser]) returns IO(false)
  mockedCloudExtensions.onUserEnable(any[SamUser], any[SamRequestContext]) returns IO.unit
  mockedCloudExtensions.onGroupUpdate(any[Seq[WorkbenchGroupIdentity]], any[SamRequestContext]) returns Future.successful(())
  mockedCloudExtensions.onUserDelete(any[WorkbenchUserId], any[SamRequestContext]) returns IO.unit

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

  private def makeUserAppearEnabled(samUser: SamUser): Unit =
    mockedCloudExtensions.getUserStatus(argThat(IsSameUserAs(samUser))) returns IO(true)

  private def makeUserAppearDisabled(samUser: SamUser): Unit =
    mockedCloudExtensions.getUserStatus(argThat(IsSameUserAs(samUser))) returns IO(false)

  def build: CloudExtensions = mockedCloudExtensions
}
