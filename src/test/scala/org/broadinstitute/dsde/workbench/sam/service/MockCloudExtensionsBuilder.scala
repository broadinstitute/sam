package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchGroup, WorkbenchGroupIdentity, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.Mockito.{RETURNS_SMART_NULLS, lenient}
import org.mockito.scalatest.MockitoSugar

import scala.concurrent.{ExecutionContext, Future}

case class MockCloudExtensionsBuilder(allUsersGroup: WorkbenchGroup) extends MockitoSugar {
  var maybeAllUsersGroup: Option[WorkbenchGroup] = None
  val mockedCloudExtensions: CloudExtensions = mock[CloudExtensions](RETURNS_SMART_NULLS)

  lenient()
    .doReturn(IO(allUsersGroup))
    .when(mockedCloudExtensions)
    .getOrCreateAllUsersGroup(any[DirectoryDAO], any[SamRequestContext])(any[ExecutionContext])

  lenient()
    .doReturn(IO.unit)
    .when(mockedCloudExtensions)
    .onUserCreate(any[SamUser], any[SamRequestContext])

  lenient()
    .doReturn(IO(false))
    .when(mockedCloudExtensions)
    .getUserStatus(any[SamUser])

  lenient()
    .doReturn(IO.unit)
    .when(mockedCloudExtensions)
    .onUserEnable(any[SamUser], any[SamRequestContext])

  lenient()
    .doReturn(Future.successful(()))
    .when(mockedCloudExtensions)
    .onGroupUpdate(any[Seq[WorkbenchGroupIdentity]], any[SamRequestContext])

  lenient()
    .doReturn(IO.unit)
    .when(mockedCloudExtensions)
    .onUserDelete(any[WorkbenchUserId], any[SamRequestContext])

  def withEnabledUser(samUser: SamUser): MockCloudExtensionsBuilder = withEnabledUsers(Set(samUser))
  def withEnabledUsers(samUsers: Iterable[SamUser]): MockCloudExtensionsBuilder = {
    samUsers.foreach(makeUserAppearEnabled)
    this
  }

  private def makeUserAppearEnabled(samUser: SamUser): Unit = {
    lenient()
      .doReturn(IO(true))
      .when(mockedCloudExtensions)
      .getUserStatus(argThat(IsSameUserAs(samUser)))
  }

  def withDisabledUser(samUser: SamUser): MockCloudExtensionsBuilder = withDisabledUsers(Set(samUser))

  def withDisabledUsers(samUsers: Iterable[SamUser]): MockCloudExtensionsBuilder = {
    samUsers.foreach(makeUserAppearDisabled)
    this
  }

  private def makeUserAppearDisabled(samUser: SamUser): Unit = {
    doReturn(IO(false))
      .when(mockedCloudExtensions)
      .getUserStatus(argThat(IsSameUserAs(samUser)))
  }

  def build: CloudExtensions = mockedCloudExtensions
}
