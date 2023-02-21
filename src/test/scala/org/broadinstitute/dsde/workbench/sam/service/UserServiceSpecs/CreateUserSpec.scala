package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchException, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.Generator.{genBasicWorkbenchGroup, genPetServiceAccount, genPolicy, genWorkbenchUserAzure, genWorkbenchUserBoth, genWorkbenchUserGoogle}
import org.broadinstitute.dsde.workbench.sam.dataAccess.MockDirectoryDaoBuilder
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
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

  val userWithBoth = (genWorkbenchUserBoth.sample.get, "user with google and azure ids")
  val userWithGoogle = (genWorkbenchUserGoogle.sample.get, "user with google id")
  val userWithAzure = (genWorkbenchUserAzure.sample.get, "user with azure id")
  val newUsers = Vector(userWithBoth, userWithGoogle, userWithAzure)


  describe("when a new user is created") {
    describe("that does not already exist") {
      newUsers.foreach { userTuple =>
        val (newUser, description) = userTuple
        describe(s"UserService.createUser with a $description") {
          val userService = TestUserServiceBuilder()
            .withAllUsersGroup(allUsersGroup)
            .build

          val userStatus = runAndWait(userService.createUser(newUser, samRequestContext))

          it should behave like createdUserInSam(newUser, userService)

          it("should return a user status with all statuses enabled besides TOS") {
            inside(userStatus) { status =>
              status should beForUser(newUser)
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
  }

  describe("UserService.createUser") {
    describe("returns an enabled UserStatus that has NOT accepted ToS") {
      it("when called for an already invited user") {
        // Setup
        val invitedUser = genWorkbenchUserBoth.sample.get
        val userService = TestUserServiceBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withInvitedUser(invitedUser)
          .build

        // Act
        val userStatus = runAndWait(userService.createUser(invitedUser, samRequestContext))

        // Assert
        inside(userStatus) { status =>
          status should beForUser(invitedUser)
          "google" should beEnabledIn(status)
          "ldap" should beEnabledIn(status)
          "allUsersGroup" should beEnabledIn(status)
          "adminEnabled" should beEnabledIn(status)
          "tosAccepted" shouldNot beEnabledIn(status)
        }
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
        val mockedDirectoryDao = MockDirectoryDaoBuilder().withAllUsersGroup(allUsersGroup).build
        val mockedCloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(mockedDirectoryDao).build
        val baseMockTosService: TosService = MockTosServiceBuilder().withAllAccepted().build
        val userService = new UserService(mockedDirectoryDao, mockedCloudExtensions, Seq(blockedDomain), baseMockTosService)

        // Act
        runAndWait(userService.createUser(samUser, samRequestContext))

        // Assert
        verify(mockedCloudExtensions)
          .onUserEnable(ArgumentMatchers.eq(samUser), any[SamRequestContext])
      }
    }

    describe("adds the new user to the `All_Users` group") {
      it("by calling the directoryDAO.addGroupMember method") {
        // Setup
        val samUser: SamUser = genWorkbenchUserBoth.sample.get
        val mockedDirectoryDao = MockDirectoryDaoBuilder().withAllUsersGroup(allUsersGroup).build
        val mockedCloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(mockedDirectoryDao).build
        val baseMockTosService: TosService = MockTosServiceBuilder().withAllAccepted().build
        val userService = new UserService(mockedDirectoryDao, mockedCloudExtensions, Seq(blockedDomain), baseMockTosService)

        // Act
        runAndWait(userService.createUser(samUser, samRequestContext))

        // Assert
        verify(mockedDirectoryDao)
          .addGroupMember(ArgumentMatchers.eq(allUsersGroup.id), ArgumentMatchers.eq(samUser.id), any[SamRequestContext])
      }
    }

    describe("fails") {
      it("when user's email is in a blocked domain") {
        // Setup
        val userWithBadEmail = genWorkbenchUserBoth.sample.get.copy(email = WorkbenchEmail(s"foo@${blockedDomain}"))
        val userService = TestUserServiceBuilder()
          .withBlockedEmailDomain(blockedDomain)
          .build

        // Act and Assert
        assertThrows[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.createUser(userWithBadEmail, samRequestContext))
        }
      }

      it("when user's email is not a properly formatted email address") {
        // Setup
        val userWithBadEmail = genWorkbenchUserBoth.sample.get.copy(email = WorkbenchEmail("I am no email address"))
        val userService = TestUserServiceBuilder().build

        // Act and Assert
        assertThrows[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.createUser(userWithBadEmail, samRequestContext))
        }
      }

      it("when user has neither an AzureB2CId nor a GoogleSubjectId") {
        // Setup
        val userWithoutIds = genWorkbenchUserBoth.sample.get.copy(googleSubjectId = None, azureB2CId = None)
        val userService = TestUserServiceBuilder()
          .withAllUsersGroup(allUsersGroup)
          .build

        // Act and Assert
        assertThrows[WorkbenchException] {
          runAndWait(userService.createUser(userWithoutIds, samRequestContext))
        }
      }

      it("when an enabled user already exists with the same AzureB2CId") {
        // Setup
        val enabledAzureUser = genWorkbenchUserAzure.sample.get
        val userService = TestUserServiceBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withEnabledUser(enabledAzureUser)
          .build
        val newUser = genWorkbenchUserAzure.sample.get.copy(azureB2CId = enabledAzureUser.azureB2CId)

        // Act and Assert
        assertThrows[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }

      it("when an enabled user already exists with the same GoogleSubjectId") {
        // Setup
        val enabledGoogleUser = genWorkbenchUserGoogle.sample.get
        val userService = TestUserServiceBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withEnabledUser(enabledGoogleUser)
          .build
        val newUser = genWorkbenchUserGoogle.sample.get.copy(googleSubjectId = enabledGoogleUser.googleSubjectId)

        // Act and Assert
        assertThrows[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }

      describe("when new user has the same email address as an existing") {
        it("FullyQualifiedPolicyId") {
          // Setup
          val somePolicy: AccessPolicy = genPolicy.sample.get
          val newUser: SamUser = genWorkbenchUserBoth.sample.get.copy(email = somePolicy.email)

          val mockDirectoryDao = MockDirectoryDaoBuilder().withAllUsersGroup(allUsersGroup).build
          when(mockDirectoryDao.loadSubjectFromEmail(ArgumentMatchers.eq(somePolicy.email), any[SamRequestContext]))
            .thenReturn(IO(Option(somePolicy.id)))

          val mockCloudExtensions = MockCloudExtensionsBuilder(mockDirectoryDao).build
          val mockTosService = MockTosServiceBuilder().withAllAccepted().build
          val baseUserService = new UserService(mockDirectoryDao, mockCloudExtensions, Seq(blockedDomain), mockTosService)

          // Act and Assert
          assertThrows[WorkbenchExceptionWithErrorReport] {
            runAndWait(baseUserService.createUser(newUser, samRequestContext))
          }
        }

        it("PetServiceAccountId") {
          // Setup
          val existingPetSA = genPetServiceAccount.sample.get
          val newUser: SamUser = genWorkbenchUserBoth.sample.get.copy(email = existingPetSA.serviceAccount.email)

          val mockDirectoryDao = MockDirectoryDaoBuilder().withAllUsersGroup(allUsersGroup).build
          when(mockDirectoryDao.loadSubjectFromEmail(ArgumentMatchers.eq(existingPetSA.serviceAccount.email), any[SamRequestContext]))
            .thenReturn(IO(Option(existingPetSA.id)))

          val mockCloudExtensions = MockCloudExtensionsBuilder(mockDirectoryDao).build
          val mockTosService = MockTosServiceBuilder().withAllAccepted().build
          val baseUserService = new UserService(mockDirectoryDao, mockCloudExtensions, Seq(blockedDomain), mockTosService)

          // Act and Assert
          assertThrows[WorkbenchExceptionWithErrorReport] {
            runAndWait(baseUserService.createUser(newUser, samRequestContext))
          }
        }

        it("WorkbenchGroupName") {
          // Setup
          val existingGroup = genBasicWorkbenchGroup.sample.get
          val newUser: SamUser = genWorkbenchUserBoth.sample.get.copy(email = existingGroup.email)

          val mockDirectoryDao = MockDirectoryDaoBuilder().withAllUsersGroup(allUsersGroup).build
          when(mockDirectoryDao.loadSubjectFromEmail(ArgumentMatchers.eq(existingGroup.email), any[SamRequestContext]))
            .thenReturn(IO(Option(existingGroup.id)))

          val mockCloudExtensions = MockCloudExtensionsBuilder(mockDirectoryDao).build
          val mockTosService = MockTosServiceBuilder().withAllAccepted().build
          val baseUserService = new UserService(mockDirectoryDao, mockCloudExtensions, Seq(blockedDomain), mockTosService)

          // Act and Assert
          assertThrows[WorkbenchExceptionWithErrorReport] {
            runAndWait(baseUserService.createUser(newUser, samRequestContext))
          }
        }
      }
    }
  }

}
