package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, SamUser}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.{RETURNS_SMART_NULLS, lenient}
import org.mockito.invocation.InvocationOnMock
import org.mockito.scalatest.MockitoSugar

object MockDirectoryDaoBuilder {
  def apply(allUsersGroup: WorkbenchGroup) =
    new MockDirectoryDaoBuilder().withAllUsersGroup(allUsersGroup)
}

case class MockDirectoryDaoBuilder() extends MockitoSugar {
  var maybeAllUsersGroup: Option[WorkbenchGroup] = None

  val mockedDirectoryDAO: DirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)

  lenient()
    .doReturn(IO(None))
    .when(mockedDirectoryDAO)
    .loadUser(any[WorkbenchUserId], any[SamRequestContext])

  lenient()
    .doReturn(IO(None))
    .when(mockedDirectoryDAO)
    .loadUserByGoogleSubjectId(any[GoogleSubjectId], any[SamRequestContext])

  lenient()
    .doReturn(IO(None))
    .when(mockedDirectoryDAO)
    .loadUserByAzureB2CId(any[AzureB2CId], any[SamRequestContext])

  lenient()
    .doReturn(IO(None))
    .when(mockedDirectoryDAO)
    .loadSubjectFromEmail(any[WorkbenchEmail], any[SamRequestContext])

  lenient()
    .doAnswer { (invocation: InvocationOnMock) =>
      IO(invocation.getArgument[SamUser](0))
    }
    .when(mockedDirectoryDAO)
    .createUser(any[SamUser], any[SamRequestContext])

  lenient()
    .doReturn(IO.unit)
    .when(mockedDirectoryDAO)
    .enableIdentity(any[WorkbenchUserId], any[SamRequestContext])

  lenient()
    .doReturn(IO.unit)
    .when(mockedDirectoryDAO)
    .disableIdentity(any[WorkbenchSubject], any[SamRequestContext])

  lenient()
    .doReturn(IO(false))
    .when(mockedDirectoryDAO)
    .isEnabled(any[WorkbenchSubject], any[SamRequestContext])

  lenient()
    .doReturn(IO(false))
    .when(mockedDirectoryDAO)
    .isGroupMember(any[WorkbenchGroupIdentity], any[WorkbenchSubject], any[SamRequestContext])

  lenient()
    .doReturn(IO(LazyList.empty))
    .when(mockedDirectoryDAO)
    .listUserDirectMemberships(any[WorkbenchUserId], any[SamRequestContext])

  lenient()
    .doReturn(IO.unit)
    .when(mockedDirectoryDAO)
    .setGoogleSubjectId(any[WorkbenchUserId], any[GoogleSubjectId], any[SamRequestContext])

  lenient()
    .doReturn(IO.unit)
    .when(mockedDirectoryDAO)
    .setUserAzureB2CId(any[WorkbenchUserId], any[AzureB2CId], any[SamRequestContext])

  lenient()
    .doReturn(IO(true))
    .when(mockedDirectoryDAO)
    .removeGroupMember(any[WorkbenchGroupIdentity], any[WorkbenchSubject], any[SamRequestContext])

  lenient()
    .doReturn(IO.unit)
    .when(mockedDirectoryDAO)
    .deleteUser(any[WorkbenchUserId], any[SamRequestContext])

  def withExistingUser(samUser: SamUser): MockDirectoryDaoBuilder = withExistingUsers(Set(samUser))
  def withExistingUsers(samUsers: Iterable[SamUser]): MockDirectoryDaoBuilder = {
    samUsers.toSet.foreach(makeUserExist)
    this
  }

  // An invited user is equivalent to a bare minimum "existing" user
  def withInvitedUser(samUser: SamUser): MockDirectoryDaoBuilder = withInvitedUsers(Set(samUser))
  def withInvitedUsers(samUsers: Iterable[SamUser]): MockDirectoryDaoBuilder = {
    samUsers.toSet.foreach(makeUserExist)
    this
  }

  def withEnabledUser(samUser: SamUser): MockDirectoryDaoBuilder = withEnabledUsers(Set(samUser))
  def withEnabledUsers(samUsers: Iterable[SamUser]): MockDirectoryDaoBuilder = {
    samUsers.toSet.foreach { u: SamUser =>
      makeUserExist(u)
      makeUserEnabled(u)
    }
    this
  }

  def withDisabledUser(samUser: SamUser): MockDirectoryDaoBuilder = withDisabledUsers(Set(samUser))

  def withDisabledUsers(samUsers: Iterable[SamUser]): MockDirectoryDaoBuilder = {
    samUsers.toSet.foreach { u: SamUser =>
      makeUserExist(u)
      makeUserEnabled(u)
      makeUserDisabled(u)
    }
    this
  }

  def withAllUsersGroup(allUsersGroup: WorkbenchGroup): MockDirectoryDaoBuilder = {
    maybeAllUsersGroup = Option(allUsersGroup)

    lenient()
      .doReturn(IO(Some(BasicWorkbenchGroup(allUsersGroup))))
      .when(mockedDirectoryDAO)
      .loadGroup(ArgumentMatchers.eq(WorkbenchGroupName(allUsersGroup.id.toString)), any[SamRequestContext])

    lenient()
      .doReturn(IO(true))
      .when(mockedDirectoryDAO)
      .addGroupMember(ArgumentMatchers.eq(allUsersGroup.id), any[WorkbenchSubject], any[SamRequestContext])

    this
  }

  // Bare minimum for a user to exist:
  // - has an ID
  // - has an email
  // - does not have a googleSubjectId
  // - does not have an azureB2CId
  // - is not enabled
  private def makeUserExist(samUser: SamUser): Unit = {
    lenient()
      .doThrow(new RuntimeException(s"User $samUser is mocked to already exist"))
      .when(mockedDirectoryDAO)
      .createUser(ArgumentMatchers.eq(samUser), any[SamRequestContext])

    // A user that only "exists" but isn't enabled or anything also does not have a Cloud Identifier
    lenient()
      .doReturn(IO(Option(samUser.copy(googleSubjectId = None, azureB2CId = None))))
      .when(mockedDirectoryDAO)
      .loadUser(ArgumentMatchers.eq(samUser.id), any[SamRequestContext])

    lenient()
      .doReturn(IO(Option(samUser.id)))
      .when(mockedDirectoryDAO)
      .loadSubjectFromEmail(ArgumentMatchers.eq(samUser.email), any[SamRequestContext])
  }

  private def makeUserEnabled(samUser: SamUser): Unit = {
    lenient()
      .doReturn(IO(true))
      .when(mockedDirectoryDAO)
      .isEnabled(ArgumentMatchers.eq(samUser.id), any[SamRequestContext])

    lenient()
      .doReturn(IO(Option(samUser.copy(enabled = true))))
      .when(mockedDirectoryDAO)
      .loadUser(ArgumentMatchers.eq(samUser.id), any[SamRequestContext])

    if (samUser.azureB2CId.nonEmpty) {
      lenient()
        .doReturn(IO(Option(samUser.copy(enabled = true))))
        .when(mockedDirectoryDAO)
        .loadUserByAzureB2CId(ArgumentMatchers.eq(samUser.azureB2CId.get), any[SamRequestContext])
    }

    if (samUser.googleSubjectId.nonEmpty) {
      lenient()
        .doReturn(IO(Option(samUser.id)))
        .when(mockedDirectoryDAO)
        .loadSubjectFromGoogleSubjectId(ArgumentMatchers.eq(samUser.googleSubjectId.get), any[SamRequestContext])
    }

    if (maybeAllUsersGroup.nonEmpty) {
      lenient()
        .doReturn(IO(true))
        .when(mockedDirectoryDAO)
        .isGroupMember(ArgumentMatchers.eq(maybeAllUsersGroup.get.id), ArgumentMatchers.eq(samUser.id), any[SamRequestContext])

      lenient()
        .doReturn(IO(LazyList(maybeAllUsersGroup.get.id)))
        .when(mockedDirectoryDAO)
        .listUserDirectMemberships(ArgumentMatchers.eq(samUser.id), any[SamRequestContext])
    }
  }

  private def makeUserDisabled(samUser: SamUser): Unit = {
    lenient()
      .doReturn(IO(false))
      .when(mockedDirectoryDAO)
      .isEnabled(ArgumentMatchers.eq(samUser.id), any[SamRequestContext])

    lenient()
      .doReturn(IO(Option(samUser.copy(enabled = false))))
      .when(mockedDirectoryDAO)
      .loadUser(ArgumentMatchers.eq(samUser.id), any[SamRequestContext])

    if (samUser.azureB2CId.nonEmpty) {
      lenient()
        .doReturn(IO(Option(samUser.copy(enabled = false))))
        .when(mockedDirectoryDAO)
        .loadUserByAzureB2CId(ArgumentMatchers.eq(samUser.azureB2CId.get), any[SamRequestContext])
    }
  }

  def build: DirectoryDAO = mockedDirectoryDAO
}
