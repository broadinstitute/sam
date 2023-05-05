package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.{RETURNS_SMART_NULLS, lenient}
import org.mockito.invocation.InvocationOnMock
import org.mockito.scalatest.MockitoSugar

import scala.collection.concurrent.TrieMap
import scala.collection.mutable

case class StatefulMockDirectoryDaoBuilder() extends MockitoSugar {
  private val groups: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = new TrieMap()
  private val groupsWithEmails: mutable.Map[WorkbenchEmail, WorkbenchGroupName] = new TrieMap()
  private val groupAccessInstructions: mutable.Map[WorkbenchGroupName, String] = new TrieMap()
  var maybeAllUsersGroup: Option[WorkbenchGroup] = None

  val mockedDirectoryDAO: DirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)

  // Default constructor state is an "empty" database.
  // Get/load requests should not return anything.
  // Inserts into tables without foreign keys should succeed
  // Inserts into tables with foreign keys should fail

  // Attempting to load any user should not find anything
  lenient()
    .doReturn(IO(None))
    .when(mockedDirectoryDAO)
    .loadUser(any[WorkbenchUserId], any[SamRequestContext])

  lenient()
    .doReturn(IO(None))
    .when(mockedDirectoryDAO)
    .loadSubjectFromGoogleSubjectId(any[GoogleSubjectId], any[SamRequestContext])

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

  /*lenient()
    .doAnswer { (invocation: InvocationOnMock) =>
      print(groups)
      val group = invocation.getArgument[BasicWorkbenchGroup](0)
      val accessInstructionsOpt = invocation.getArgument[Option[String]](1)
      if (groups.keySet.contains(group.id)) {
        IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"group ${group.id} already exists")))
      } else {
        groups += group.id -> group
        groupsWithEmails += group.email -> group.id
        accessInstructionsOpt match {
          case Some(s) =>
            groupAccessInstructions += group.id -> s
          case _ => ()
        }
        IO.pure(group)
      }
    }
    .when(mockedDirectoryDAO)
    .createGroup(any[BasicWorkbenchGroup], any[Option[String]], any[SamRequestContext])*/

  // Create/Insert user should succeed and then make the user appear to "exist" in the Mock
  lenient()
    .doAnswer { (invocation: InvocationOnMock) =>
      val samUser = invocation.getArgument[SamUser](0)
      makeUserExist(samUser)
      IO(samUser)
    }
    .when(mockedDirectoryDAO)
    .createUser(any[SamUser], any[SamRequestContext])

  // Default behavior can check if the user "exists" in the Mock and respond accordingly
  lenient()
    .doAnswer { (invocation: InvocationOnMock) =>
      val samUserId = invocation.getArgument[WorkbenchUserId](0)
      val samRequestContext = invocation.getArgument[SamRequestContext](1)
      val maybeUser = mockedDirectoryDAO.loadUser(samUserId, samRequestContext).unsafeRunSync()
      maybeUser match {
        case Some(samUser) => makeUserEnabled(samUser)
        case None => throw new RuntimeException("Mocking error when trying to enable a user that does not exist")
      }
      IO.unit
    }
    .when(mockedDirectoryDAO)
    .enableIdentity(any[WorkbenchUserId], any[SamRequestContext])

  lenient()
    .doAnswer { (invocation: InvocationOnMock) =>
      val subject = invocation.getArgument[WorkbenchUserId](0)
      val samRequestContext = invocation.getArgument[SamRequestContext](1)
      val maybeUser = mockedDirectoryDAO.loadUser(subject, samRequestContext).unsafeRunSync()
      maybeUser match {
        case Some(samUser) => makeUserDisabled(samUser)
        case None => throw new RuntimeException("Mocking error when trying to disable a user that does not exist")
      }
      IO.unit
    }
    .when(mockedDirectoryDAO)
    .disableIdentity(any[WorkbenchSubject], any[SamRequestContext])

  // No users "exist" so there are a bunch of queries that should return false/None if they depend on "existing" users
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

  // Note, these methods don't actually set any "state" in the mocked DAO.  If you need some sort of coordinated
  // state in your mocks, you should mock these methods yourself in your tests
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

  def withExistingUser(samUser: SamUser): StatefulMockDirectoryDaoBuilder = withExistingUsers(Set(samUser))
  def withExistingUsers(samUsers: Iterable[SamUser]): StatefulMockDirectoryDaoBuilder = {
    samUsers.toSet.foreach(makeUserExist)
    this
  }

  // An invited user is equivalent to a bare minimum "existing" user
  def withInvitedUser(samUser: SamUser): StatefulMockDirectoryDaoBuilder = withInvitedUsers(Set(samUser))
  def withInvitedUsers(samUsers: Iterable[SamUser]): StatefulMockDirectoryDaoBuilder = {
    samUsers.toSet.foreach(makeUserExist)
    this
  }

  def withEnabledUser(samUser: SamUser): StatefulMockDirectoryDaoBuilder = withEnabledUsers(Set(samUser))
  def withEnabledUsers(samUsers: Iterable[SamUser]): StatefulMockDirectoryDaoBuilder = {
    samUsers.toSet.foreach { u: SamUser =>
      makeUserExist(u)
      makeUserEnabled(u)
    }
    this
  }

  def withDisabledUser(samUser: SamUser): StatefulMockDirectoryDaoBuilder = withDisabledUsers(Set(samUser))

  def withDisabledUsers(samUsers: Iterable[SamUser]): StatefulMockDirectoryDaoBuilder = {
    samUsers.toSet.foreach { u: SamUser =>
      makeUserExist(u)
      makeUserEnabled(u)
      makeUserDisabled(u)
    }
    this
  }

  def withAllUsersGroup(allUsersGroup: WorkbenchGroup): StatefulMockDirectoryDaoBuilder = {
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

  def withWorkbenchGroup(group: WorkbenchGroup, accessInstructionsOpt: Option[String] = None): StatefulMockDirectoryDaoBuilder = {
    groups += group.id -> group
    groupsWithEmails += group.email -> group.id
    accessInstructionsOpt match {
      case Some(s) =>
        groupAccessInstructions += group.id -> s
      case _ => ()
    }

    doThrow(new RuntimeException(s"Group $group is mocked to already exist"))
      .when(mockedDirectoryDAO)
      .createGroup(ArgumentMatchers.eq(BasicWorkbenchGroup(group)), any[Option[String]], any[SamRequestContext])

    lenient()
      .doReturn(IO.pure(Option(group)))
      .when(mockedDirectoryDAO)
      .loadGroup(ArgumentMatchers.eq(WorkbenchGroupName(group.id.toString)), any[SamRequestContext])

    this
  }
  // Bare minimum for a user to exist:
  // - has an ID
  // - has an email
  // - does not have a googleSubjectId
  // - does not have an azureB2CId
  // - is not enabled
  private def makeUserExist(samUser: SamUser): Unit = {
    doThrow(new RuntimeException(s"User $samUser is mocked to already exist"))
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

      lenient()
        .doReturn(IO(Option(samUser)))
        .when(mockedDirectoryDAO)
        .loadUserByGoogleSubjectId(ArgumentMatchers.eq(samUser.googleSubjectId.get), any[SamRequestContext])
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
