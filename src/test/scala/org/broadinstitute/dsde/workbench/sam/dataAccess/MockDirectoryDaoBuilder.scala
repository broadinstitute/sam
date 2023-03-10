package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, SamUser}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchersSugar.{any, eqTo}
import org.mockito.IdiomaticMockito.StubbingOps
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.mockito.MockitoSugar

object MockDirectoryDaoBuilder {
  def apply(allUsersGroup: WorkbenchGroup) =
    new MockDirectoryDaoBuilder().withAllUsersGroup(allUsersGroup)
}

case class MockDirectoryDaoBuilder() extends MockitoSugar {
  var maybeAllUsersGroup: Option[WorkbenchGroup] = None

  val mockedDirectoryDAO: DirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)

  mockedDirectoryDAO.loadUser(any[WorkbenchUserId], any[SamRequestContext]) returns IO(None)
  mockedDirectoryDAO.loadUserByGoogleSubjectId(any[GoogleSubjectId], any[SamRequestContext]) returns IO(None)
  mockedDirectoryDAO.loadUserByAzureB2CId(any[AzureB2CId], any[SamRequestContext]) returns IO(None)
  mockedDirectoryDAO.loadSubjectFromEmail(any[WorkbenchEmail], any[SamRequestContext]) returns IO(None)
  mockedDirectoryDAO.createUser(any[SamUser], any[SamRequestContext]) answers ((u: SamUser, _: SamRequestContext) => IO(u))
  mockedDirectoryDAO.enableIdentity(any[WorkbenchUserId], any[SamRequestContext]) returns IO.unit
  mockedDirectoryDAO.disableIdentity(any[WorkbenchSubject], any[SamRequestContext]) returns IO.unit
  mockedDirectoryDAO.isEnabled(any[WorkbenchSubject], any[SamRequestContext]) returns IO(false)
  mockedDirectoryDAO.isGroupMember(any[WorkbenchGroupIdentity], any[WorkbenchSubject], any[SamRequestContext]) returns IO(false)
  mockedDirectoryDAO.listUserDirectMemberships(any[WorkbenchUserId], any[SamRequestContext]) returns IO(LazyList.empty)
  mockedDirectoryDAO.setGoogleSubjectId(any[WorkbenchUserId], any[GoogleSubjectId], any[SamRequestContext]) returns IO.unit
  mockedDirectoryDAO.setUserAzureB2CId(any[WorkbenchUserId], any[AzureB2CId], any[SamRequestContext]) returns IO.unit
  mockedDirectoryDAO.removeGroupMember(any[WorkbenchGroupIdentity], any[WorkbenchSubject], any[SamRequestContext]) returns IO(true)
  mockedDirectoryDAO.deleteUser(any[WorkbenchUserId], any[SamRequestContext]) returns IO.unit

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

  // WorkbenchSubjects are weird, they are not full multi-parameter objects, but just identifiers.
  // Most or all objects identified with a WorkbenchSubject id also have an email.
  def withWorkbenchSubject(subject: WorkbenchSubject, subjectsEmail: WorkbenchEmail): MockDirectoryDaoBuilder = {
    mockedDirectoryDAO.loadSubjectFromEmail(eqTo(subjectsEmail), any[SamRequestContext]) returns IO(Option(subject))
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
    mockedDirectoryDAO.loadGroup(eqTo(WorkbenchGroupName(allUsersGroup.id.toString)), any[SamRequestContext]) returns IO(Some(BasicWorkbenchGroup(allUsersGroup)))
    mockedDirectoryDAO.addGroupMember(eqTo(allUsersGroup.id), any[WorkbenchSubject], any[SamRequestContext]) returns IO(true)
    this
  }

  // Bare minimum for a user to exist:
  // - has an ID
  // - has an email
  // - does not have a googleSubjectId
  // - does not have an azureB2CId
  // - is not enabled
  private def makeUserExist(samUser: SamUser): Unit = {
    mockedDirectoryDAO.createUser(eqTo(samUser), any[SamRequestContext]) throws new RuntimeException(s"User $samUser is mocked to already exist")
    mockedDirectoryDAO.loadUser(eqTo(samUser.id), any[SamRequestContext]) returns IO(Option(samUser.copy(googleSubjectId = None, azureB2CId = None)))
    mockedDirectoryDAO.loadSubjectFromEmail(eqTo(samUser.email), any[SamRequestContext]) returns IO(Option(samUser.id))
  }

  private def makeUserEnabled(samUser: SamUser): Unit = {
    mockedDirectoryDAO.isEnabled(eqTo(samUser.id), any[SamRequestContext]) returns IO(true)
    mockedDirectoryDAO.loadUser(eqTo(samUser.id), any[SamRequestContext]) returns IO(Option(samUser.copy(enabled = true)))

    if (samUser.azureB2CId.nonEmpty) {
      mockedDirectoryDAO.loadUserByAzureB2CId(eqTo(samUser.azureB2CId.get), any[SamRequestContext]) returns IO(Option(samUser.copy(enabled = true)))
    }

    if (samUser.googleSubjectId.nonEmpty) {
      mockedDirectoryDAO.loadSubjectFromGoogleSubjectId(eqTo(samUser.googleSubjectId.get), any[SamRequestContext]) returns IO(Option(samUser.id))
      mockedDirectoryDAO.loadUserByGoogleSubjectId(eqTo(samUser.googleSubjectId.get), any[SamRequestContext]) returns IO(Some(samUser))
    }

    if (maybeAllUsersGroup.nonEmpty) {
      mockedDirectoryDAO.isGroupMember(eqTo(maybeAllUsersGroup.get.id), eqTo(samUser.id), any[SamRequestContext]) returns IO(true)
      mockedDirectoryDAO.listUserDirectMemberships(eqTo(samUser.id), any[SamRequestContext]) returns IO(LazyList(maybeAllUsersGroup.get.id))
    }
  }

  private def makeUserDisabled(samUser: SamUser): Unit = {
    mockedDirectoryDAO.isEnabled(eqTo(samUser.id), any[SamRequestContext]) returns IO(false)
    mockedDirectoryDAO.loadUser(eqTo(samUser.id), any[SamRequestContext]) returns IO(Option(samUser.copy(enabled = false)))

    if (samUser.googleSubjectId.nonEmpty) {
      mockedDirectoryDAO.loadUserByGoogleSubjectId(eqTo(samUser.googleSubjectId.get), any[SamRequestContext]) returns IO(Option(samUser.copy(enabled = false)))
    }

    if (samUser.azureB2CId.nonEmpty) {
      mockedDirectoryDAO.loadUserByAzureB2CId(eqTo(samUser.azureB2CId.get), any[SamRequestContext]) returns IO(Option(samUser.copy(enabled = false)))
    }
  }

  def build: DirectoryDAO = mockedDirectoryDAO
}
