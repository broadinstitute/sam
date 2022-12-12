package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, SamUser}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{RETURNS_SMART_NULLS, doReturn, doThrow, when}
import org.mockito.invocation.InvocationOnMock
import org.scalatestplus.mockito.MockitoSugar.mock

class MockDirectoryDaoBuilder() {
  var maybeAllUsersGroup: Option[WorkbenchGroup] = None

  val mockedDirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
  when(mockedDirectoryDAO.loadUser(any[WorkbenchUserId], any[SamRequestContext]))
    .thenReturn(IO(None))
  when(mockedDirectoryDAO.loadSubjectFromGoogleSubjectId(any[GoogleSubjectId], any[SamRequestContext]))
    .thenReturn(IO(None))
  when(mockedDirectoryDAO.loadSubjectFromEmail(any[WorkbenchEmail], any[SamRequestContext]))
    .thenReturn(IO(None))
  when(mockedDirectoryDAO.createUser(any[SamUser], any[SamRequestContext]))
    .thenAnswer((invocation: InvocationOnMock) => {
      val samUser = invocation.getArgument[SamUser](0)
      makeUserExist(samUser)
      IO(samUser)
    })

  // Intended to have `enableIdentity` throw an exception, but because GoogleExtensions calls `enableIdentity` for
  // petServiceAccounts it causes problems.  So this is how it is for now.
  when(mockedDirectoryDAO.enableIdentity(any[WorkbenchUserId], any[SamRequestContext]))
    // .thenThrow(new RuntimeException("Mocked exception because no users exist to enable"))
    .thenReturn(IO.unit)

  when(mockedDirectoryDAO.addGroupMember(any[WorkbenchGroupIdentity], any[WorkbenchSubject], any[SamRequestContext]))
    .thenThrow(new RuntimeException("Mocked exception.  Use `MockDirectoryDaoBuilder.withAllUsersGroup()`"))

  def withExistingUser(samUser: SamUser): MockDirectoryDaoBuilder = {
    makeUserExist(samUser)
    this
  }

  def withAllUsersGroup(allUsersGroup: WorkbenchGroup): MockDirectoryDaoBuilder = {
    maybeAllUsersGroup = Option(allUsersGroup)

    when(mockedDirectoryDAO.loadGroup(ArgumentMatchers.eq(WorkbenchGroupName(allUsersGroup.id.toString)), any[SamRequestContext]))
      .thenReturn(IO(Some(BasicWorkbenchGroup(allUsersGroup))))

    doReturn(IO(true))
      .when(mockedDirectoryDAO).addGroupMember(ArgumentMatchers.eq(allUsersGroup.id), any[WorkbenchSubject], any[SamRequestContext])
    this
  }

  private def makeUserExist(samUser: SamUser): Unit = {
    doThrow(new RuntimeException(s"User ${samUser} is mocked to already exist"))
      .when(mockedDirectoryDAO).createUser(ArgumentMatchers.eq(samUser), any[SamRequestContext])
    when(mockedDirectoryDAO.loadUser(ArgumentMatchers.eq(samUser.id), any[SamRequestContext])).thenReturn(IO(Option(samUser)))
    // Syntax needs to be slightly different here to properly take precedence over the default `.thenThrow` behavior.  Not sure why...Mockito
    doReturn(IO.unit).when(mockedDirectoryDAO).enableIdentity(ArgumentMatchers.eq(samUser.id), any[SamRequestContext])
    when(mockedDirectoryDAO.loadSubjectFromEmail(ArgumentMatchers.eq(samUser.email), any[SamRequestContext]))
      .thenReturn(IO(Option(samUser.id)))

    when(mockedDirectoryDAO.enableIdentity(ArgumentMatchers.eq(samUser.id), any[SamRequestContext]))
      .thenAnswer(_ => {
        makeUserAppearEnabled(samUser)
        IO.unit
      })

    if (samUser.googleSubjectId.nonEmpty) {
      when(mockedDirectoryDAO.loadSubjectFromGoogleSubjectId(ArgumentMatchers.eq(samUser.googleSubjectId.get), any[SamRequestContext]))
        .thenReturn(IO(Option(samUser.id)))
    }
  }

  private def makeUserAppearEnabled(samUser: SamUser): Unit = {
    when(mockedDirectoryDAO.isEnabled(ArgumentMatchers.eq(samUser.id), any[SamRequestContext]))
      .thenReturn(IO(true))

    when(mockedDirectoryDAO.loadUser(ArgumentMatchers.eq(samUser.id), any[SamRequestContext]))
      .thenReturn(IO(Option(samUser.copy(enabled = true))))

    if (samUser.azureB2CId.nonEmpty) {
      when(mockedDirectoryDAO.loadUserByAzureB2CId(ArgumentMatchers.eq(samUser.azureB2CId.get), any[SamRequestContext]))
        .thenReturn(IO(Option(samUser.copy(enabled = true))))
    }

    if (maybeAllUsersGroup.nonEmpty) {
      when(mockedDirectoryDAO.isGroupMember(
        ArgumentMatchers.eq(maybeAllUsersGroup.get.id),
        ArgumentMatchers.eq(samUser.id),
        any[SamRequestContext])
      ).thenReturn(IO(true))

    }
  }

  def build(): DirectoryDAO = mockedDirectoryDAO
}

//    when(dirDAO.loadUser(defaultUser.id, samRequestContext)).thenReturn(IO(Some(enabledUser)))
//    when(dirDAO.loadSubjectFromGoogleSubjectId(defaultUser.googleSubjectId.get, samRequestContext)).thenReturn(IO(None))
//    when(dirDAO.loadSubjectFromEmail(defaultUser.email, samRequestContext)).thenReturn(IO(Option(defaultUser.id)))
//    when(dirDAO.createUser(defaultUser, samRequestContext)).thenReturn(IO(disabledUser))
//    when(dirDAO.deleteUser(defaultUser.id, samRequestContext)).thenReturn(IO(()))
//    when(dirDAO.enableIdentity(defaultUser.id, samRequestContext)).thenReturn(IO(()))
//    when(dirDAO.disableIdentity(defaultUser.id, samRequestContext)).thenReturn(IO(()))
//    when(dirDAO.addGroupMember(allUsersGroup.id, defaultUser.id, samRequestContext)).thenReturn(IO(true))
//    when(dirDAO.removeGroupMember(allUsersGroup.id, defaultUser.id, samRequestContext)).thenReturn(IO(true))
//    when(dirDAO.isGroupMember(allUsersGroup.id, defaultUser.id, samRequestContext)).thenReturn(IO(true))
//    when(dirDAO.isEnabled(defaultUser.id, samRequestContext)).thenReturn(IO(true))
//    when(dirDAO.listUserDirectMemberships(defaultUser.id, samRequestContext)).thenReturn(IO(LazyList(allUsersGroup.id)))
//    when(dirDAO.setGoogleSubjectId(defaultUser.id, defaultUser.googleSubjectId.get, samRequestContext)).thenReturn(IO(()))
//    when(dirDAO.setUserAzureB2CId(defaultUser.id, defaultUser.azureB2CId.get, samRequestContext)).thenReturn(IO(()))