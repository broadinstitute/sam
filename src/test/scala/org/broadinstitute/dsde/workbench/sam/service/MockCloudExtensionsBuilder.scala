package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.model.WorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.{ArgumentMatcher, ArgumentMatchers}
import org.mockito.ArgumentMatchers.{any, argThat}
import org.mockito.Mockito.{RETURNS_SMART_NULLS, doReturn, when}
import org.mockito.invocation.InvocationOnMock
import org.scalatestplus.mockito.MockitoSugar.mock

import scala.concurrent.{ExecutionContext, Future}

// It is probably not a good thing that GoogleExtensions (the implementation of CloudExtensions) needs a DirectoryDAO
// so that it can _update_ the database, but it does.  It makes things more complicated here.
class MockCloudExtensionsBuilder(directoryDAO: DirectoryDAO) {
  var maybeAllUsersGroup: Option[WorkbenchGroup] = None
  val mockedCloudExtensions: CloudExtensions = mock[CloudExtensions](RETURNS_SMART_NULLS)

  /**
    * Constructor logic mocks up CloudExtensions as if in an "empty" state
    */

  // Surprisingly, the implementation will try to create the All Users group in the Sam database if it does not
  // already exist.  It probably shouldn't do that, but it does.  Mocking similar behavior here.
  when(mockedCloudExtensions.getOrCreateAllUsersGroup(ArgumentMatchers.eq(directoryDAO), any[SamRequestContext])(any[ExecutionContext]))
    .thenAnswer((invocation: InvocationOnMock) => {
      val samRequestContext = invocation.getArgument[SamRequestContext](1)
      val maybeGroup = directoryDAO.loadGroup(CloudExtensions.allUsersGroupName, samRequestContext).unsafeRunSync()
      maybeGroup match {
        case Some(group) => Future.successful(group)
        case None =>
          throw new RuntimeException("Mocked exception.  Make sure the `directoryDAO` used to construct this " +
            s"MockCloudExtensionsBuilder has an '${CloudExtensions.allUsersGroupName}' group in it.  If using a " +
            s"mock `directoryDAO`, try building it with `MockDirectoryDaoBuilder.withAllUsersGroup()`")
      }
    })

  when(mockedCloudExtensions.onUserCreate(any[SamUser], any[SamRequestContext]))
    .thenAnswer((invocation: InvocationOnMock) => {
      val samUser = invocation.getArgument[SamUser](0)
      makeUserExist(samUser)
      Future.successful(())
    })

  when(mockedCloudExtensions.getUserStatus(any[SamUser]))
    .thenReturn(Future.successful(false))

  when(mockedCloudExtensions.onUserEnable(any[SamUser], any[SamRequestContext]))
    .thenReturn(Future.successful(()))

  private def makeUserExist(samUser: SamUser): Unit = {
    // Real implementation just returns unit if the user already exists
    doReturn(Future.successful(()))
      .when(mockedCloudExtensions).onUserCreate(ArgumentMatchers.eq(samUser), any[SamRequestContext])
    doReturn(Future.successful(true))
      .when(mockedCloudExtensions).getUserStatus(argThat(IsSameUserAs(samUser)))
    doReturn(Future.successful(()))
      .when(mockedCloudExtensions).onUserEnable(ArgumentMatchers.eq(samUser), any[SamRequestContext])
  }

  def build(): CloudExtensions = mockedCloudExtensions
}

case class IsSameUserAs(user: SamUser) extends ArgumentMatcher[SamUser] {
  def matches(otherUser: SamUser): Boolean = user.id == otherUser.id
}
