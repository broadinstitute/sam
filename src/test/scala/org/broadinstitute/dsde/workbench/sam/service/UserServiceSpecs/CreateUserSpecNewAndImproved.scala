package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.Generator.{genWorkbenchUserBoth, genWorkbenchUserGoogle}
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, TosService, UserService}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.RETURNS_SMART_NULLS

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

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

      it("should not be able to register with an invalid email address") {
        // Arrange
        val newUser = genWorkbenchUserBoth.sample.get.copy(email = WorkbenchEmail("potato"))

        // Act and Assert
        intercept[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }

      it("should not be able to register with a valid email address for a blocked domain") {
        // Arrange
        val blockedDomain: String = "evilCorp.com"
        val newUser = genWorkbenchUserBoth.sample.get.copy(email = WorkbenchEmail(s"BadGuyBob@$blockedDomain"))
        val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq(blockedDomain), tosService)

        // Act and Assert
        intercept[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }

      it("if their GoogleSubjectId matches an already registered user") {
        // Arrange
        val newUser = genWorkbenchUserBoth.sample.get
        doReturn(IO(Some(genWorkbenchUserGoogle.sample.get.copy(googleSubjectId = newUser.googleSubjectId))))
          .when(directoryDAO)
          .loadUserByGoogleSubjectId(ArgumentMatchers.eq(newUser.googleSubjectId.get), any[SamRequestContext])

        // Act and Assert
        intercept[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }
    }
  }
}
