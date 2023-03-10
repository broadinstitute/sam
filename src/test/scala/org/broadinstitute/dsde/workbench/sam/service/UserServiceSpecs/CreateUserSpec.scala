package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.Generator.{genWorkbenchUserAzure, genWorkbenchUserBoth, genWorkbenchUserGoogle}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDaoBuilder}
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, SamUser}
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchersSugar.{any, eqTo}
import org.scalatest.DoNotDiscover

import scala.concurrent.ExecutionContextExecutor

@DoNotDiscover
class CreateUserSpec extends UserServiceTestTraits {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))
  val defaultTosService: TosService = MockTosServiceBuilder().build

  describe("A new User") {

    describe("should be able to register") {

      // Not sure if this test can happen in the real world anymore now that we always log users in through B2C.
      // Keeping it here for now just in case.
      it("after authenticating with Google") {
        // Arrange
        val newGoogleUser = genWorkbenchUserGoogle.sample.get
        val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
        val cloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
        val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, defaultTosService)

        // Act
        val newUsersStatus = runAndWait(userService.createUser(newGoogleUser, samRequestContext))

        // Assert
        inside(newUsersStatus) { status =>
          status should beForUser(newGoogleUser)
          "google" should beEnabledIn(status)
          "ldap" should beEnabledIn(status)
          "allUsersGroup" should beEnabledIn(status)
          "adminEnabled" should beEnabledIn(status)
          "tosAccepted" shouldNot beEnabledIn(status)
        }
      }

      it("after authenticating with Microsoft") {
        // Arrange
        val newAzureUser = genWorkbenchUserAzure.sample.get
        val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
        val cloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
        val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, defaultTosService)

        // Act
        val newUsersStatus = runAndWait(userService.createUser(newAzureUser, samRequestContext))

        // Assert
        inside(newUsersStatus) { status =>
          status should beForUser(newAzureUser)
          "google" should beEnabledIn(status)
          "ldap" should beEnabledIn(status)
          "allUsersGroup" should beEnabledIn(status)
          "adminEnabled" should beEnabledIn(status)
          "tosAccepted" shouldNot beEnabledIn(status)
        }
      }

      it("after authenticating with Google through Azure B2C") {
        // Arrange
        val newUserWithBothCloudIds = genWorkbenchUserBoth.sample.get
        val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
        val cloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
        val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, defaultTosService)

        // Act
        val newUsersStatus = runAndWait(userService.createUser(newUserWithBothCloudIds, samRequestContext))

        // Assert
        inside(newUsersStatus) { status =>
          status should beForUser(newUserWithBothCloudIds)
          "google" should beEnabledIn(status)
          "ldap" should beEnabledIn(status)
          "allUsersGroup" should beEnabledIn(status)
          "adminEnabled" should beEnabledIn(status)
          "tosAccepted" shouldNot beEnabledIn(status)
        }
      }
    }

    describe("should not be able to register ") {

      it("with an invalid email address") {
        // Arrange
        val newUser = genWorkbenchUserBoth.sample.get.copy(email = WorkbenchEmail("potato"))
        val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
        val cloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
        val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, defaultTosService)

        // Act and Assert
        a [WorkbenchExceptionWithErrorReport] should be thrownBy {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }

      it("with an email address in a blocked domain") {
        // Arrange
        val blockedDomain: String = "evilCorp.com"
        val newUser = genWorkbenchUserBoth.sample.get.copy(email = WorkbenchEmail(s"BadGuyBob@$blockedDomain"))
        val directoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
        val cloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
        val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq(blockedDomain), defaultTosService)

        // Act and Assert
        a [WorkbenchExceptionWithErrorReport] should be thrownBy {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }

      it("with an email address that is already used by a non-user") {
        // Arrange
        val newUser = genWorkbenchUserBoth.sample.get
        val conflictingWorkbenchSubjectEmail = newUser.email
        val directoryDAO = MockDirectoryDaoBuilder()
          .withWorkbenchSubject(WorkbenchGroupName("potato"), conflictingWorkbenchSubjectEmail)
          .build
        val cloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
        val userService = new UserService(directoryDAO, cloudExtensions, Seq.empty, defaultTosService)

        // Act and Assert
        a [WorkbenchExceptionWithErrorReport] should be thrownBy {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }

      it("if their GoogleSubjectId matches an already registered user") {
        // Arrange
        val newUser = genWorkbenchUserGoogle.sample.get
        val somebodyElse = genWorkbenchUserGoogle.sample.get.copy(googleSubjectId = newUser.googleSubjectId)
        val directoryDAO = MockDirectoryDaoBuilder(allUsersGroup)
          .withEnabledUser(somebodyElse)
          .build
        val cloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
        val userService = new UserService(directoryDAO, cloudExtensions, Seq.empty, defaultTosService)

        // Act and Assert
        a [WorkbenchExceptionWithErrorReport] should be thrownBy {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }

      it("if their AzureB2CId matches an already registered user") {
        // Arrange
        val newUser = genWorkbenchUserAzure.sample.get
        val somebodyElse = genWorkbenchUserAzure.sample.get.copy(azureB2CId = newUser.azureB2CId)
        val directoryDAO = MockDirectoryDaoBuilder(allUsersGroup)
          .withEnabledUser(somebodyElse)
          .build
        val cloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
        val userService = new UserService(directoryDAO, cloudExtensions, Seq.empty, defaultTosService)

        // Act and Assert
        a [WorkbenchExceptionWithErrorReport] should be thrownBy {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }

      it("if they have neither an AzureB2CId nor a GoogleSubjectId") {
        // Setup
        val userWithoutIds = genWorkbenchUserBoth.sample.get.copy(googleSubjectId = None, azureB2CId = None)
        val directoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
        val cloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
        val userService = new UserService(directoryDAO, cloudExtensions, Seq.empty, defaultTosService)

        // Act and Assert
        assertThrows[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.createUser(userWithoutIds, samRequestContext))
        }
      }
    }

    describe("should be added to the All Users group") {

      describe("when successfully registering") {

        it("as a new User") {
          // Arrange
          val newGoogleUser = genWorkbenchUserGoogle.sample.get
          val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
          val cloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
          val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, defaultTosService)

          // Act
          runAndWait(userService.createUser(newGoogleUser, samRequestContext))

          // Assert
          directoryDAO.addGroupMember(
            allUsersGroup.id,
            newGoogleUser.id,
            samRequestContext) wasCalled once
        }

        it("as an invited User") {
          // Arrange
          val invitedUser = genWorkbenchUserGoogle.sample.get
          val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).withInvitedUser(invitedUser).build
          val cloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
          val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, defaultTosService)

          // Act
          runAndWait(userService.createUser(invitedUser, samRequestContext))

          // Assert
          directoryDAO.addGroupMember(
            allUsersGroup.id,
            invitedUser.id,
            samRequestContext) wasCalled once
        }
      }
    }

    describe("should be enabled on Google") {

      describe("when successfully registering") {

        it("as a new User") {
          // Arrange
          val newGoogleUser = genWorkbenchUserGoogle.sample.get
          val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
          val cloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
          val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, defaultTosService)

          // Act
          runAndWait(userService.createUser(newGoogleUser, samRequestContext))

          // Assert
          cloudExtensions.onUserEnable(
            eqTo(newGoogleUser),
            any[SamRequestContext]
          ) wasCalled once
        }

        it("as an invited User") {
          // Arrange
          val invitedUser = genWorkbenchUserGoogle.sample.get
          val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).withInvitedUser(invitedUser).build
          val cloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
          val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, defaultTosService)

          // Act
          runAndWait(userService.createUser(invitedUser, samRequestContext))

          // Assert
          cloudExtensions.onUserEnable(
            eqTo(invitedUser),
            any[SamRequestContext]
          ) wasCalled once
        }
      }
    }
  }

  describe("An invited User") {

    describe("should be able to register") {

      it("after authenticating with Google") {
        // Arrange
        val invitedGoogleUser = genWorkbenchUserGoogle.sample.get
        val directoryDAO = MockDirectoryDaoBuilder(allUsersGroup)
          .withInvitedUser(invitedGoogleUser)
          .build
        val cloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
        val userService = new UserService(directoryDAO, cloudExtensions, Seq.empty, defaultTosService)

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

      it("after authenticating with Microsoft") {
        // Arrange
        val invitedAzureUser = genWorkbenchUserAzure.sample.get
        val directoryDAO = MockDirectoryDaoBuilder(allUsersGroup)
          .withInvitedUser(invitedAzureUser)
          .build
        val cloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
        val userService = new UserService(directoryDAO, cloudExtensions, Seq.empty, defaultTosService)

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
        val invitedUser = genWorkbenchUserBoth.sample.get
        val directoryDAO = MockDirectoryDaoBuilder(allUsersGroup)
          .withInvitedUser(invitedUser)
          .build
        val cloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
        val userService = new UserService(directoryDAO, cloudExtensions, Seq.empty, defaultTosService)

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

      // Test arose out of: https://broadworkbench.atlassian.net/browse/PROD-677
      it("and keep the same user ID") {
        // Arrange
        val invitedUser = genWorkbenchUserBoth.sample.get
        val disregardedId = WorkbenchUserId("122333444455555")
        val registeringUser = invitedUser.copy(id = disregardedId)
        val directoryDAO = MockDirectoryDaoBuilder(allUsersGroup)
          .withInvitedUser(invitedUser)
          .build
        val cloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
        val userService = new UserService(directoryDAO, cloudExtensions, Seq.empty, defaultTosService)

        // Act
        runAndWait(userService.createUser(registeringUser, samRequestContext))

        // Assert
        directoryDAO.createUser(
          any[SamUser],
          any[SamRequestContext]) wasNever called
        directoryDAO.setGoogleSubjectId(
          eqTo(disregardedId),
          any[GoogleSubjectId],
          any[SamRequestContext]) wasNever called
        directoryDAO.setUserAzureB2CId(
          eqTo(disregardedId),
          any[AzureB2CId],
          any[SamRequestContext]) wasNever called
      }
    }
  }
}
