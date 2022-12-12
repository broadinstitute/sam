package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchException, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.Generator.{genWorkbenchUserAzure, genWorkbenchUserBoth, genWorkbenchUserGoogle}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDaoBuilder}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, MockCloudExtensionsBuilder, TosService, UserService}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Inside, OptionValues}
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.ExecutionContextExecutor

class CreateUserSpec extends AnyFunSpec with Matchers with TestSupport with MockitoSugar with ScalaFutures with Inside with OptionValues {

  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  val defaultUser: SamUser = genWorkbenchUserBoth.sample.get

  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  val baseMockedDirectoryDao: DirectoryDAO = new MockDirectoryDaoBuilder().withAllUsersGroup(allUsersGroup).build()
  val baseMockedCloudExtensions: CloudExtensions = new MockCloudExtensionsBuilder(baseMockedDirectoryDao).build()

  val baseMockTosService: TosService = mock[TosService](RETURNS_SMART_NULLS)
  when(baseMockTosService.getTosStatus(any[WorkbenchUserId], any[SamRequestContext])).thenReturn(IO(Option(true)))

  val blockedDomain = "blocked.domain.com"
  val baseUserService = new UserService(baseMockedDirectoryDao, baseMockedCloudExtensions, Seq(blockedDomain), baseMockTosService)

  describe("UserService.createUser") {
    describe("returns a fully enabled UserStatus") {
      it("when user has an AzureB2CId and a GoogleSubjectId") {
        val userWithBothIds = genWorkbenchUserBoth.sample.get
        val expectedUserStatus = new UserStatusBuilder(userWithBothIds).build

        val userStatus = runAndWait(baseUserService.createUser(userWithBothIds, samRequestContext))

        userStatus shouldBe expectedUserStatus
      }

      it("when user has an AzureB2CId but no GoogleSubjectId") {
        val userWithOnlyB2CId = genWorkbenchUserAzure.sample.get
        val expectedUserStatus = new UserStatusBuilder(userWithOnlyB2CId).build

        val userStatus = runAndWait(baseUserService.createUser(userWithOnlyB2CId, samRequestContext))

        userStatus shouldBe expectedUserStatus
      }

      it("when user has GoogleSubjectId but no AzureB2CId") {
        val userWithOnlyGoogleId = genWorkbenchUserGoogle.sample.get
        val expectedUserStatus = new UserStatusBuilder(userWithOnlyGoogleId).build

        val userStatus = runAndWait(baseUserService.createUser(userWithOnlyGoogleId, samRequestContext))

        userStatus shouldBe expectedUserStatus
      }
    }

    describe("fails") {
      it("when user's email is in a blocked domain") {
        val userWithBadEmail = defaultUser.copy(email = WorkbenchEmail(s"foo@${blockedDomain}"))
        assertThrows[WorkbenchExceptionWithErrorReport] {
          runAndWait(baseUserService.createUser(userWithBadEmail, samRequestContext))
        }
      }

      it("when user's email is not a properly formatted email address") {
        val userWithBadEmail = defaultUser.copy(email = WorkbenchEmail("foo"))
        assertThrows[WorkbenchExceptionWithErrorReport] {
          runAndWait(baseUserService.createUser(userWithBadEmail, samRequestContext))
        }
      }

      it("when user has neither an AzureB2CId nor a GoogleSubjectId") {
        val userWithoutIds = defaultUser.copy(googleSubjectId = None, azureB2CId = None)
        assertThrows[WorkbenchException] {
          runAndWait(baseUserService.createUser(userWithoutIds, samRequestContext))
        }
      }

      it("when a user already exists with the same AzureB2CId") {
        // Setup
        val existingAzureUser = genWorkbenchUserAzure.sample.get
        val mockedDirectoryDao: DirectoryDAO = new MockDirectoryDaoBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withExistingUser(existingAzureUser)
          .build()
        val mockedCloudExtensions: CloudExtensions = new MockCloudExtensionsBuilder(mockedDirectoryDao).build()
        val userService = new UserService(mockedDirectoryDao, mockedCloudExtensions, Seq(blockedDomain), baseMockTosService)
        val newUser = genWorkbenchUserAzure.sample.get.copy(azureB2CId = existingAzureUser.azureB2CId)

        // Act and Assert
        assertThrows[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }

      it("when a user already exists with the same GoogleSubjectId") {
        // Setup
        val existingGoogleUser = genWorkbenchUserGoogle.sample.get
        val mockedDirectoryDao: DirectoryDAO = new MockDirectoryDaoBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withExistingUser(existingGoogleUser)
          .build()
        val mockedCloudExtensions: CloudExtensions = new MockCloudExtensionsBuilder(mockedDirectoryDao).build()
        val userService = new UserService(mockedDirectoryDao, mockedCloudExtensions, Seq(blockedDomain), baseMockTosService)
        val newUser = genWorkbenchUserGoogle.sample.get.copy(googleSubjectId = existingGoogleUser.googleSubjectId)

        // Act and Assert
        assertThrows[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.createUser(newUser, samRequestContext))
        }
      }
    }
  }

}
