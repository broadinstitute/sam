package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, SamUser}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{RETURNS_SMART_NULLS, doAnswer, doReturn, doThrow}
import org.mockito.invocation.InvocationOnMock
import org.scalatestplus.mockito.MockitoSugar.mock

case class MockDirectoryDaoBuilder() {
  var maybeAllUsersGroup: Option[WorkbenchGroup] = None

  val mockedDirectoryDAO: DirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)

  // Default constructor state is an "empty" database.
  // Get/load requests should not return anything.
  // Inserts into tables without foreign keys should succeed
  // Inserts into tables with foreign keys should fail

  // Attempting to load any user should not find anything
  doReturn(IO(None))
    .when(mockedDirectoryDAO)
    .loadUser(any[WorkbenchUserId], any[SamRequestContext])

  doReturn(IO(None))
    .when(mockedDirectoryDAO)
    .loadSubjectFromGoogleSubjectId(any[GoogleSubjectId], any[SamRequestContext])

  doReturn(IO(None))
    .when(mockedDirectoryDAO)
    .loadUserByAzureB2CId(any[AzureB2CId], any[SamRequestContext])

  doReturn(IO(None))
    .when(mockedDirectoryDAO)
    .loadSubjectFromEmail(any[WorkbenchEmail], any[SamRequestContext])

  // Create/Insert user should succeed and then make the user appear to "exist" in the Mock
  doAnswer { (invocation: InvocationOnMock) =>
    val samUser = invocation.getArgument[SamUser](0)
    makeUserExist(samUser)
    IO(samUser)
  }.when(mockedDirectoryDAO)
   .createUser(any[SamUser], any[SamRequestContext])

  // Default behavior can check if the user "exists" in the Mock and respond accordingly
  doAnswer { (invocation: InvocationOnMock) =>
    val samUserId = invocation.getArgument[WorkbenchUserId](0)
    val samRequestContext = invocation.getArgument[SamRequestContext](1)
    val maybeUser = mockedDirectoryDAO.loadUser(samUserId, samRequestContext).unsafeRunSync()
    maybeUser match {
      case Some(samUser) => makeUserAppearEnabled(samUser)
      case None => throw new RuntimeException("Mocking error when trying to enable a user that does not exist")
    }
    IO.unit
  }.when(mockedDirectoryDAO)
   .enableIdentity(any[WorkbenchUserId], any[SamRequestContext])

  // No users "exist" so there are a bunch of queries that should return false/None if they depend on "existing" users
  doReturn(IO(false))
    .when(mockedDirectoryDAO)
    .isEnabled(any[WorkbenchSubject], any[SamRequestContext])

  doReturn(IO(false))
    .when(mockedDirectoryDAO)
    .isGroupMember(any[WorkbenchGroupIdentity], any[WorkbenchSubject], any[SamRequestContext])

  doReturn(IO(LazyList.empty))
    .when(mockedDirectoryDAO)
    .listUserDirectMemberships(any[WorkbenchUserId], any[SamRequestContext])

  // Note, these methods don't actually set any "state" in the mocked DAO.  If you need some sort of coordinated
  // state in your mocks, you should mock these methods yourself in your tests
  doReturn(IO.unit)
    .when(mockedDirectoryDAO)
    .setGoogleSubjectId(any[WorkbenchUserId], any[GoogleSubjectId], any[SamRequestContext])
  doReturn(IO.unit)
    .when(mockedDirectoryDAO)
    .setUserAzureB2CId(any[WorkbenchUserId], any[AzureB2CId], any[SamRequestContext])

  def withExistingUser(samUser: SamUser): MockDirectoryDaoBuilder = {
    makeUserExist(samUser)
    this
  }

  def withInvitedUser(samUser: SamUser): MockDirectoryDaoBuilder = withExistingUser(samUser)

  def withEnabledUser(samUser: SamUser): MockDirectoryDaoBuilder = {
    makeUserExist(samUser)
    makeUserAppearEnabled(samUser)
    this
  }

  def withAllUsersGroup(allUsersGroup: WorkbenchGroup): MockDirectoryDaoBuilder = {
    maybeAllUsersGroup = Option(allUsersGroup)

    doReturn(IO(Some(BasicWorkbenchGroup(allUsersGroup))))
      .when(mockedDirectoryDAO)
      .loadGroup(ArgumentMatchers.eq(WorkbenchGroupName(allUsersGroup.id.toString)), any[SamRequestContext])

    doReturn(IO(true))
      .when(mockedDirectoryDAO)
      .addGroupMember(ArgumentMatchers.eq(allUsersGroup.id), any[WorkbenchSubject], any[SamRequestContext])

    this
  }

  // Bare minimum existing user has an ID and an email, but no googleSubjectId or azureB2CId
  private def makeUserExist(samUser: SamUser): Unit = {
    doThrow(new RuntimeException(s"User $samUser is mocked to already exist"))
      .when(mockedDirectoryDAO)
      .createUser(ArgumentMatchers.eq(samUser), any[SamRequestContext])

    // A user that only "exists" but isn't enabled or anything also does not have a Cloud Identifier
    doReturn(IO(Option(samUser.copy(googleSubjectId = None, azureB2CId = None))))
      .when(mockedDirectoryDAO)
      .loadUser(ArgumentMatchers.eq(samUser.id), any[SamRequestContext])

    doReturn(IO(Option(samUser.id)))
      .when(mockedDirectoryDAO)
      .loadSubjectFromEmail(ArgumentMatchers.eq(samUser.email), any[SamRequestContext])
  }

  private def makeUserAppearEnabled(samUser: SamUser): Unit = {
    doReturn(IO(true))
      .when(mockedDirectoryDAO)
      .isEnabled(ArgumentMatchers.eq(samUser.id), any[SamRequestContext])

    doReturn(IO(Option(samUser.copy(enabled = true))))
      .when(mockedDirectoryDAO)
      .loadUser(ArgumentMatchers.eq(samUser.id), any[SamRequestContext])

    if (samUser.azureB2CId.nonEmpty) {
      doReturn(IO(Option(samUser.copy(enabled = true))))
        .when(mockedDirectoryDAO)
        .loadUserByAzureB2CId(ArgumentMatchers.eq(samUser.azureB2CId.get), any[SamRequestContext])
    }

    if (samUser.googleSubjectId.nonEmpty) {
      doReturn(IO(Option(samUser.id)))
        .when(mockedDirectoryDAO)
        .loadSubjectFromGoogleSubjectId(ArgumentMatchers.eq(samUser.googleSubjectId.get), any[SamRequestContext])
    }

    if (maybeAllUsersGroup.nonEmpty) {
      doReturn(IO(true))
        .when(mockedDirectoryDAO)
        .isGroupMember(ArgumentMatchers.eq(maybeAllUsersGroup.get.id), ArgumentMatchers.eq(samUser.id), any[SamRequestContext])

      doReturn(IO(LazyList(maybeAllUsersGroup.get.id)))
        .when(mockedDirectoryDAO)
        .listUserDirectMemberships(ArgumentMatchers.eq(samUser.id), any[SamRequestContext])
    }
  }

  def build: DirectoryDAO = mockedDirectoryDAO
}
