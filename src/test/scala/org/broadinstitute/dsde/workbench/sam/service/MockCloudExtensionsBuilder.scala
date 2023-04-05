package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.model.{WorkbenchGroup, WorkbenchGroupIdentity, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.util.health.{SubsystemStatus, Subsystems}
import org.mockito.Mockito.{RETURNS_SMART_NULLS, lenient}
import org.mockito.invocation.InvocationOnMock
import org.mockito.scalatest.MockitoSugar
import org.mockito.{ArgumentMatcher, ArgumentMatchers}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

// It is probably not a good thing that GoogleExtensions (the implementation of CloudExtensions) needs a DirectoryDAO
// so that it can _update_ the database, but it does.  It makes things more complicated here.
case class MockCloudExtensionsBuilder(directoryDAO: DirectoryDAO) extends MockitoSugar {
  var maybeAllUsersGroup: Option[WorkbenchGroup] = None
  val mockedCloudExtensions: CloudExtensions = mock[CloudExtensions](RETURNS_SMART_NULLS)

  /** Constructor logic mocks up CloudExtensions as if in an "empty" state
    */

  // Surprisingly, the implementation will try to create the 'All Users' group in the Sam database if it does not
  // already exist.  It probably shouldn't do that, but it does.  Mocking similar behavior here.
  lenient()
    .doAnswer { (invocation: InvocationOnMock) =>
      val samRequestContext = invocation.getArgument[SamRequestContext](1)
      val maybeGroup = directoryDAO.loadGroup(CloudExtensions.allUsersGroupName, samRequestContext).unsafeRunSync()
      maybeGroup match {
        case Some(group) => IO(group)
        case None =>
          throw new RuntimeException(
            "Mocked exception.  Make sure the `directoryDAO` used to construct this " +
              s"MockCloudExtensionsBuilder has an '${CloudExtensions.allUsersGroupName}' group in it.  If using a " +
              s"mock `directoryDAO`, try building it with `MockDirectoryDaoBuilder.withAllUsersGroup()`"
          )
      }
    }
    .when(mockedCloudExtensions)
    .getOrCreateAllUsersGroup(ArgumentMatchers.eq(directoryDAO), any[SamRequestContext])(any[ExecutionContext])

  lenient()
    .doAnswer { (invocation: InvocationOnMock) =>
      val samUser = invocation.getArgument[SamUser](0)
      makeUserAppearEnabled(samUser)
      IO.unit
    }
    .when(mockedCloudExtensions)
    .onUserCreate(any[SamUser], any[SamRequestContext])

  lenient()
    .doReturn(IO(false))
    .when(mockedCloudExtensions)
    .getUserStatus(any[SamUser])

  lenient()
    .doAnswer { (invocation: InvocationOnMock) =>
      val samUser = invocation.getArgument[SamUser](0)
      makeUserAppearEnabled(samUser)
      IO.unit
    }
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

  lenient()
    .doReturn(Set.empty)
    .when(mockedCloudExtensions)
    .allSubSystems

  lenient()
    .doReturn(Map.empty)
    .when(mockedCloudExtensions)
    .checkStatus

  def withEnabledUser(samUser: SamUser): MockCloudExtensionsBuilder = withEnabledUsers(Set(samUser))
  def withEnabledUsers(samUsers: Iterable[SamUser]): MockCloudExtensionsBuilder = {
    samUsers.foreach(makeUserAppearEnabled)
    this
  }

  private def makeUserAppearEnabled(samUser: SamUser): Unit = {
    // Real implementation just returns unit if the user already exists
    lenient()
      .doReturn(IO.unit)
      .when(mockedCloudExtensions)
      .onUserCreate(argThat(IsSameUserAs(samUser)), any[SamRequestContext])
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

  private val subsystemStatuses: mutable.Map[Subsystems.Subsystem, Future[SubsystemStatus]] = mutable.Map.empty

  def withHealthySubsystem(subsystem: Subsystems.Subsystem): MockCloudExtensionsBuilder = {
    subsystemStatuses.addOne(subsystem -> Future.successful(SubsystemStatus(true, None)))
    this
  }

  def withUnhealthySubsystem(subsystem: Subsystems.Subsystem, messages: List[String]): MockCloudExtensionsBuilder = {
    subsystemStatuses.addOne(subsystem -> Future.successful(SubsystemStatus(false, Some(messages))))
    this
  }

  private def makeUserAppearDisabled(samUser: SamUser): Unit = {
    // Real implementation just returns unit if the user already exists
    doReturn(IO.unit)
      .when(mockedCloudExtensions)
      .onUserCreate(argThat(IsSameUserAs(samUser)), any[SamRequestContext])
    doReturn(IO(false))
      .when(mockedCloudExtensions)
      .getUserStatus(argThat(IsSameUserAs(samUser)))
  }

  def build: CloudExtensions = {
    lenient()
      .doReturn(subsystemStatuses.toMap)
      .when(mockedCloudExtensions)
      .checkStatus

    mockedCloudExtensions
  }
}

case class IsSameUserAs(user: SamUser) extends ArgumentMatcher[SamUser] {
  def matches(otherUser: SamUser): Boolean = user.id == otherUser.id
}
