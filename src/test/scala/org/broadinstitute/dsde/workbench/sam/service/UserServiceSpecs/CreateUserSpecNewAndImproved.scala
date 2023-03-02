package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchGroupIdentity, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.Generator.{genWorkbenchUserAzure, genWorkbenchUserBoth, genWorkbenchUserGoogle}
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, TosService, UserService}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.RETURNS_SMART_NULLS

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

class CreateUserSpecNewAndImproved extends UserServiceTestTraits {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  val directoryDAO: DirectoryDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)
  val cloudExtensions: CloudExtensions = mock[CloudExtensions](RETURNS_SMART_NULLS)
  val tosService: TosService = mock[TosService](RETURNS_SMART_NULLS)
  val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

  // Begin mocks for CloudExtensions
  doReturn(IO(allUsersGroup))
    .when(cloudExtensions)
    .getOrCreateAllUsersGroup(any[DirectoryDAO], any[SamRequestContext])(any[ExecutionContext])

  describe("A new User") {
    it("should be able to register") {
      // Arrange
      val newUser = genWorkbenchUserBoth.sample.get
      doReturn(IO(None))
        .when(directoryDAO)
        .loadUserByGoogleSubjectId(ArgumentMatchers.eq(newUser.googleSubjectId.get), any[SamRequestContext])
      doReturn(IO(None))
        .when(directoryDAO)
        .loadSubjectFromEmail(ArgumentMatchers.eq(newUser.email), any[SamRequestContext])
      doReturn(IO(newUser))
        .when(directoryDAO)
        .createUser(ArgumentMatchers.eq(newUser), any[SamRequestContext])
      doReturn(IO.unit)
        .when(directoryDAO)
        .enableIdentity(ArgumentMatchers.eq(newUser.id), any[SamRequestContext])
      doReturn(IO(true))
        .when(directoryDAO)
        .addGroupMember(ArgumentMatchers.eq(allUsersGroup.id), ArgumentMatchers.eq(newUser.id), any[SamRequestContext])
      doReturn(IO.unit)
        .when(cloudExtensions)
        .onUserCreate(ArgumentMatchers.eq(newUser), any[SamRequestContext])
      doReturn(IO.unit)
        .when(cloudExtensions)
        .onUserEnable(ArgumentMatchers.eq(newUser), any[SamRequestContext])

      // Act
      val newUsersStatus = runAndWait(userService.createUser(newUser, samRequestContext))

      // Assert
      inside(newUsersStatus) { status =>
        status should beForUser(newUser)
        "google" should beEnabledIn(status)
        "ldap" should beEnabledIn(status)
        "allUsersGroup" should beEnabledIn(status)
        "adminEnabled" should beEnabledIn(status)
        "tosAccepted" shouldNot beEnabledIn(status)
      }
    }

    describe("should not be able to register ") {

      it("with an invalid email address") {
        // Arrange
        val newUser = genWorkbenchUserBoth.sample.get.copy(email = WorkbenchEmail("potato"))

        // Act and Assert
        intercept[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }

      it("with an email address in a blocked domain") {
        // Arrange
        val blockedDomain: String = "evilCorp.com"
        val newUser = genWorkbenchUserBoth.sample.get.copy(email = WorkbenchEmail(s"BadGuyBob@$blockedDomain"))
        val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq(blockedDomain), tosService)

        // Act and Assert
        intercept[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }

      it("with an email address that is already used by a non-user") {
        // Arrange
        val newUser = genWorkbenchUserBoth.sample.get
        // For example, when we look up a subject by email it might return a Workbench Group named "potato"
        doReturn(IO(Option(WorkbenchGroupName("potato"))))
          .when(directoryDAO)
          .loadSubjectFromEmail(ArgumentMatchers.eq(newUser.email), any[SamRequestContext])
        doReturn(IO(None))
          .when(directoryDAO)
          .loadUserByGoogleSubjectId(ArgumentMatchers.eq(newUser.googleSubjectId.get), any[SamRequestContext])

        // Act and Assert
        intercept[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }

      it("if their GoogleSubjectId matches an already registered user") {
        // Arrange
        val newUser = genWorkbenchUserGoogle.sample.get
        doReturn(IO(Some(genWorkbenchUserGoogle.sample.get.copy(googleSubjectId = newUser.googleSubjectId))))
          .when(directoryDAO)
          .loadUserByGoogleSubjectId(ArgumentMatchers.eq(newUser.googleSubjectId.get), any[SamRequestContext])

        // Act and Assert
        intercept[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }

      it("if their AzureB2CId matches an already registered user") {
        // Arrange
        val newUser = genWorkbenchUserAzure.sample.get
        doReturn(IO(Some(genWorkbenchUserGoogle.sample.get.copy(azureB2CId = newUser.azureB2CId))))
          .when(directoryDAO)
          .loadUserByAzureB2CId(ArgumentMatchers.eq(newUser.azureB2CId.get), any[SamRequestContext])

        // Act and Assert
        intercept[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }
    }
  }

  describe("An invited User") {
    val invitedUser = genWorkbenchUserBoth.sample.get

    describe("should be able to register") {
      doReturn(IO(Option(invitedUser.id)))
        .when(directoryDAO)
        .loadSubjectFromEmail(ArgumentMatchers.eq(invitedUser.email), any[SamRequestContext])
      doReturn(IO(LazyList(allUsersGroup.id)))
        .when(directoryDAO)
        .listUserDirectMemberships(ArgumentMatchers.eq(invitedUser.id), any[SamRequestContext])
      doReturn(IO.unit)
        .when(directoryDAO)
        .enableIdentity(ArgumentMatchers.eq(invitedUser.id), any[SamRequestContext])
      doReturn(IO(true))
        .when(directoryDAO)
        .addGroupMember(ArgumentMatchers.eq(allUsersGroup.id), ArgumentMatchers.eq(invitedUser.id), any[SamRequestContext])
      doReturn(Future.successful(()))
        .when(cloudExtensions)
        .onGroupUpdate(any[Seq[WorkbenchGroupIdentity]], any[SamRequestContext])

      it("with a GoogleSubjectId") {
        // Arrange
        val invitedGoogleUser = invitedUser.copy(azureB2CId = None)
        doReturn(IO(None))
          .when(directoryDAO)
          .loadUserByGoogleSubjectId(ArgumentMatchers.eq(invitedGoogleUser.googleSubjectId.get), any[SamRequestContext])
        doReturn(IO.unit)
          .when(directoryDAO)
          .setGoogleSubjectId(ArgumentMatchers.eq(invitedGoogleUser.id), ArgumentMatchers.eq(invitedGoogleUser.googleSubjectId.get), any[SamRequestContext])
        doReturn(IO.unit)
          .when(cloudExtensions)
          .onUserEnable(ArgumentMatchers.eq(invitedGoogleUser), any[SamRequestContext])

        // Act
        val newUsersStatus = runAndWait(userService.createUser(invitedGoogleUser, samRequestContext))

        // Assert
        inside(newUsersStatus) { status =>
          status should beForUser(invitedGoogleUser)
          "google" should beEnabledIn(status)
          "ldap" should beEnabledIn(status)
          "allUsersGroup" should beEnabledIn(status)
          "adminEnabled" should beEnabledIn(status)
          "tosAccepted" shouldNot beEnabledIn(status)
        }
      }

      it("with an AzureB2CId") {
        // Arrange
        val invitedAzureUser = invitedUser.copy(googleSubjectId = None)
        doReturn(IO(None))
          .when(directoryDAO)
          .loadUserByAzureB2CId(ArgumentMatchers.eq(invitedAzureUser.azureB2CId.get), any[SamRequestContext])
        doReturn(IO.unit)
          .when(directoryDAO)
          .setUserAzureB2CId(ArgumentMatchers.eq(invitedAzureUser.id), ArgumentMatchers.eq(invitedAzureUser.azureB2CId.get), any[SamRequestContext])
        doReturn(IO.unit)
          .when(cloudExtensions)
          .onUserEnable(ArgumentMatchers.eq(invitedAzureUser), any[SamRequestContext])

        // Act
        val newUsersStatus = runAndWait(userService.createUser(invitedAzureUser, samRequestContext))

        // Assert
        inside(newUsersStatus) { status =>
          status should beForUser(invitedAzureUser)
          "google" should beEnabledIn(status)
          "ldap" should beEnabledIn(status)
          "allUsersGroup" should beEnabledIn(status)
          "adminEnabled" should beEnabledIn(status)
          "tosAccepted" shouldNot beEnabledIn(status)
        }
      }

      it("with both a GoogleSubjectId and an AzureB2CId") {
        // Arrange
        doReturn(IO(None))
          .when(directoryDAO)
          .loadUserByAzureB2CId(ArgumentMatchers.eq(invitedUser.azureB2CId.get), any[SamRequestContext])
        doReturn(IO.unit)
          .when(directoryDAO)
          .setUserAzureB2CId(ArgumentMatchers.eq(invitedUser.id), ArgumentMatchers.eq(invitedUser.azureB2CId.get), any[SamRequestContext])
        doReturn(IO(None))
          .when(directoryDAO)
          .loadUserByGoogleSubjectId(ArgumentMatchers.eq(invitedUser.googleSubjectId.get), any[SamRequestContext])
        doReturn(IO.unit)
          .when(directoryDAO)
          .setGoogleSubjectId(ArgumentMatchers.eq(invitedUser.id), ArgumentMatchers.eq(invitedUser.googleSubjectId.get), any[SamRequestContext])
        doReturn(IO.unit)
          .when(cloudExtensions)
          .onUserEnable(ArgumentMatchers.eq(invitedUser), any[SamRequestContext])

        // Act
        val newUsersStatus = runAndWait(userService.createUser(invitedUser, samRequestContext))

        // Assert
        inside(newUsersStatus) { status =>
          status should beForUser(invitedUser)
          "google" should beEnabledIn(status)
          "ldap" should beEnabledIn(status)
          "allUsersGroup" should beEnabledIn(status)
          "adminEnabled" should beEnabledIn(status)
          "tosAccepted" shouldNot beEnabledIn(status)
        }
      }
    }
  }
}
