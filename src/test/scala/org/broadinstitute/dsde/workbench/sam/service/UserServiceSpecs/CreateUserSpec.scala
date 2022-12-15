package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchException, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.Generator.{genBasicWorkbenchGroup, genPetServiceAccount, genPolicy, genWorkbenchUserAzure, genWorkbenchUserBoth, genWorkbenchUserGoogle}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDaoBuilder}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, MockCloudExtensionsBuilder, MockTosServiceBuilder, TosService, UserService}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._

import scala.concurrent.ExecutionContextExecutor

class CreateUserSpec extends UserServiceTestTraits {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  // Setup test vals
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))
  val blockedDomain = "blocked.domain.com"

  // Setup mocks
  val baseMockedDirectoryDao: DirectoryDAO = MockDirectoryDaoBuilder().withAllUsersGroup(allUsersGroup).build
  val baseMockedCloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(baseMockedDirectoryDao).build

  val baseMockTosService: TosService = MockTosServiceBuilder().withAllAccepted().build
  when(baseMockTosService.getTosStatus(any[WorkbenchUserId], any[SamRequestContext])).thenReturn(IO(Option(true)))

  // Setup a UserService that can be used in most of the tests
  val baseUserService = new UserService(baseMockedDirectoryDao, baseMockedCloudExtensions, Seq(blockedDomain), baseMockTosService)

  describe("UserService.createUser") {
    describe("returns a fully enabled UserStatus") {
      it("when user has an AzureB2CId and a GoogleSubjectId") {
        // Setup
        val userWithBothIds = genWorkbenchUserBoth.sample.get
        val expectedUserStatus = UserStatusBuilder(userWithBothIds).build

        // Act
        val userStatus = runAndWait(baseUserService.createUser(userWithBothIds, samRequestContext))

        // Assert
        userStatus shouldBe expectedUserStatus
      }

      it("when user has an AzureB2CId but no GoogleSubjectId") {
        // Setup
        val userWithOnlyB2CId = genWorkbenchUserAzure.sample.get
        val expectedUserStatus = UserStatusBuilder(userWithOnlyB2CId).build

        // Act
        val userStatus = runAndWait(baseUserService.createUser(userWithOnlyB2CId, samRequestContext))

        // Assert
        userStatus shouldBe expectedUserStatus
      }

      it("when user has GoogleSubjectId but no AzureB2CId") {
        // Setup
        val userWithOnlyGoogleId = genWorkbenchUserGoogle.sample.get
        val expectedUserStatus = UserStatusBuilder(userWithOnlyGoogleId).build

        // Act
        val userStatus = runAndWait(baseUserService.createUser(userWithOnlyGoogleId, samRequestContext))

        // Assert
        userStatus shouldBe expectedUserStatus
      }

      it("when called for an already invited user") {
        // Setup
        val invitedUser = genWorkbenchUserBoth.sample.get
        val mockedDirectoryDao: DirectoryDAO = MockDirectoryDaoBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withInvitedUser(invitedUser)
          .build
        val mockedCloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(mockedDirectoryDao).build
        val userService = new UserService(mockedDirectoryDao, mockedCloudExtensions, Seq.empty, baseMockTosService)
        val expectedUserStatus = UserStatusBuilder(invitedUser).build

        // Act
        val userStatus = runAndWait(userService.createUser(invitedUser, samRequestContext))

        // Assert
        userStatus shouldBe expectedUserStatus
      }
    }

    describe("enables the user on Google") {
      it("by calling the cloudExtensions.onUserEnable method") {
        // This is not a great test as it is testing implementation, not functionality.  But due to the way the
        // interactions with Google are implemented, I think this is the best we can do for now.  With some refactoring,
        // we can probably test the logic of what it means to enable a user on Google without actually calling Google.
        // For now, this is where we need to mock for our test to work without actually calling Google and our
        // integration test for GoogleCloudExtensions will need to test the business logic for what it means to enable
        // a user, yuck.
        // Setup
        val samUser: SamUser = genWorkbenchUserBoth.sample.get

        // Act
        runAndWait(baseUserService.createUser(samUser, samRequestContext))

        // Assert
        verify(baseMockedCloudExtensions)
          .onUserEnable(ArgumentMatchers.eq(samUser), any[SamRequestContext])
      }
    }

    describe("adds the new user to the `All_Users` group") {
      it("by calling the directoryDAO.addGroupMember method") {
        // Setup
        val samUser: SamUser = genWorkbenchUserBoth.sample.get

        // Act
        runAndWait(baseUserService.createUser(samUser, samRequestContext))

        // Assert
        verify(baseMockedDirectoryDao)
          .addGroupMember(ArgumentMatchers.eq(allUsersGroup.id), ArgumentMatchers.eq(samUser.id), any[SamRequestContext])
      }
    }

    describe("fails") {
      it("when user's email is in a blocked domain") {
        // Setup
        val userWithBadEmail = genWorkbenchUserBoth.sample.get.copy(email = WorkbenchEmail(s"foo@${blockedDomain}"))

        // Act and Assert
        assertThrows[WorkbenchExceptionWithErrorReport] {
          runAndWait(baseUserService.createUser(userWithBadEmail, samRequestContext))
        }
      }

      it("when user's email is not a properly formatted email address") {
        // Setup
        val userWithBadEmail = genWorkbenchUserBoth.sample.get.copy(email = WorkbenchEmail("foo"))

        // Act and Assert
        assertThrows[WorkbenchExceptionWithErrorReport] {
          runAndWait(baseUserService.createUser(userWithBadEmail, samRequestContext))
        }
      }

      it("when user has neither an AzureB2CId nor a GoogleSubjectId") {
        // Setup
        val userWithoutIds = genWorkbenchUserBoth.sample.get.copy(googleSubjectId = None, azureB2CId = None)

        // Act and Assert
        assertThrows[WorkbenchException] {
          runAndWait(baseUserService.createUser(userWithoutIds, samRequestContext))
        }
      }

      it("when an enabled user already exists with the same AzureB2CId") {
        // Setup
        val enabledAzureUser = genWorkbenchUserAzure.sample.get
        val mockedDirectoryDao: DirectoryDAO = MockDirectoryDaoBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withFullyActivatedUser(enabledAzureUser)
          .build
        val mockedCloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(mockedDirectoryDao).build
        val userService = new UserService(mockedDirectoryDao, mockedCloudExtensions, Seq(blockedDomain), baseMockTosService)
        val newUser = genWorkbenchUserAzure.sample.get.copy(azureB2CId = enabledAzureUser.azureB2CId)

        // Act and Assert
        assertThrows[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }

      it("when an enabled user already exists with the same GoogleSubjectId") {
        // Setup
        val enabledGoogleUser = genWorkbenchUserGoogle.sample.get
        val mockedDirectoryDao: DirectoryDAO = MockDirectoryDaoBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withFullyActivatedUser(enabledGoogleUser)
          .build
        val mockedCloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(mockedDirectoryDao).build
        val userService = new UserService(mockedDirectoryDao, mockedCloudExtensions, Seq(blockedDomain), baseMockTosService)
        val newUser = genWorkbenchUserGoogle.sample.get.copy(googleSubjectId = enabledGoogleUser.googleSubjectId)

        // Act and Assert
        assertThrows[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }

      describe("when new user has the same email address with an existing") {
        it("FullyQualifiedPolicyId") {
          // Setup
          val somePolicy: AccessPolicy = genPolicy.sample.get
          val newUser: SamUser = genWorkbenchUserBoth.sample.get.copy(email = somePolicy.email)
          when(baseMockedDirectoryDao.loadSubjectFromEmail(ArgumentMatchers.eq(somePolicy.email), any[SamRequestContext]))
            .thenReturn(IO(Option(somePolicy.id)))

          // Act and Assert
          assertThrows[WorkbenchExceptionWithErrorReport] {
            runAndWait(baseUserService.createUser(newUser, samRequestContext))
          }
        }

        it("PetServiceAccountId") {
          // Setup
          val existingPetSA = genPetServiceAccount.sample.get
          val newUser: SamUser = genWorkbenchUserBoth.sample.get.copy(email = existingPetSA.serviceAccount.email)
          when(baseMockedDirectoryDao.loadSubjectFromEmail(ArgumentMatchers.eq(existingPetSA.serviceAccount.email), any[SamRequestContext]))
            .thenReturn(IO(Option(existingPetSA.id)))

          // Act and Assert
          assertThrows[WorkbenchExceptionWithErrorReport] {
            runAndWait(baseUserService.createUser(newUser, samRequestContext))
          }
        }

        it("WorkbenchGroupName") {
          // Setup
          val existingGroup = genBasicWorkbenchGroup.sample.get
          val newUser: SamUser = genWorkbenchUserBoth.sample.get.copy(email = existingGroup.email)
          when(baseMockedDirectoryDao.loadSubjectFromEmail(ArgumentMatchers.eq(existingGroup.email), any[SamRequestContext]))
            .thenReturn(IO(Option(existingGroup.id)))

          // Act and Assert
          assertThrows[WorkbenchExceptionWithErrorReport] {
            runAndWait(baseUserService.createUser(newUser, samRequestContext))
          }
        }
      }
    }
  }

}
