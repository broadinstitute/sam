package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model.{AzureB2CId, GoogleSubjectId, WorkbenchEmail, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.Generator.{genWorkbenchUserAzure, genWorkbenchUserBoth, genWorkbenchUserGoogle}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.dataAccess.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.model.api.AdminUpdateUserRequest
import org.broadinstitute.dsde.workbench.sam.service._

import java.util.UUID
import scala.concurrent.ExecutionContextExecutor

class UpdateUserSpec extends UserServiceTestTraits {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  describe("When updating") {
    describe("an existing user's email") {
      describe("with a valid email") {
        it("should be successful") {
          // Arrange
          val userWithBothIds = genWorkbenchUserBoth.sample.get
          val userService = TestUserServiceBuilder()
            .withAllUsersGroup(allUsersGroup)
            .withEnabledUser(userWithBothIds)
            .build
          val email = "goodemail@gmail.com"
          val request = AdminUpdateUserRequest(None, None, Some(WorkbenchEmail(email)), None)
          // Act
          val response = runAndWait(userService.updateUserCrud(userWithBothIds.id, request, samRequestContext))

          // Assert
          assert(response.nonEmpty, "Updating a user with a valid email should return an object")
          assert(
            response.get equals userWithBothIds.copy(enabled = true, email = WorkbenchEmail(email)),
            "Updating a user with a valid email should return the corresponding user after update."
          )
        }
      }
      describe("with a invalid email") {
        it("should be unsuccessful") {
          // Arrange
          val userWithBothIds = genWorkbenchUserBoth.sample.get
          val userService = TestUserServiceBuilder()
            .withAllUsersGroup(allUsersGroup)
            .withEnabledUser(userWithBothIds)
            .build
          val email = "bademail.com"
          val request = AdminUpdateUserRequest(None, None, Some(WorkbenchEmail(email)), None)

          // Act
          val error = intercept[WorkbenchExceptionWithErrorReport] {
            runAndWait(userService.updateUserCrud(userWithBothIds.id, request, samRequestContext))
          }

          // Assert
          assert(error.errorReport.statusCode.value equals StatusCodes.BadRequest, "Updating a user with an invalid email should have a Bad Request status")
          assert(error.errorReport.message equals "invalid user update", "Updating a user with an invalid email should send back a invalid update message")
          assert(
            error.errorReport.causes.head.message equals s"invalid email address [$email]",
            "Updating a user with an invalid email should send back the correct cause"
          )
        }
      }
    }
    describe("a user that does not exist") {
      it("should be unsuccessful") {
        // Arrange
        val userWithBothIds = genWorkbenchUserBoth.sample.get
        val userService = TestUserServiceBuilder()
          .withAllUsersGroup(allUsersGroup)
          .build
        val request = AdminUpdateUserRequest(None, None, Some(WorkbenchEmail("goodemail@gmail.com")), None)
        // Act
        val response = runAndWait(userService.updateUserCrud(userWithBothIds.id, request, samRequestContext))

        // Assert
        assert(response.isEmpty, "Updating a nonexistent user should not find a user that it updated")
      }
    }
  }
  describe("an existing user's azureB2CId") {
    describe("with a valid azureB2CId") {
      it("should be successful") {
        // Arrange
        val userWithBothIds = genWorkbenchUserBoth.sample.get
        val directoryDAO = new MockDirectoryDAO
        val cloudExtensions = StatefulMockCloudExtensionsBuilder(directoryDAO).build
        val tosService = mock[TosService]
        val userService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

        val newAzureB2CId = UUID.randomUUID().toString
        val updateRequest = AdminUpdateUserRequest(Some(AzureB2CId(newAzureB2CId)), None, None, None)

        TestSupport.runAndWait(directoryDAO.createUser(userWithBothIds, samRequestContext))

        // Act
        val response = runAndWait(userService.updateUserCrud(userWithBothIds.id, updateRequest, samRequestContext))

        // Assert
        assert(response.nonEmpty, "Updating a user with a valid azureB2CId should return an object")
        assert(
          response.get equals userWithBothIds.copy(azureB2CId = Some(AzureB2CId(newAzureB2CId))),
          "Updating a user with a valid azureB2CId should return the corresponding user after update."
        )
      }
    }

    describe("with an invalid azureB2CId") {
      it("should be unsuccessful") {
        // Arrange
        val userWithBothIds = genWorkbenchUserBoth.sample.get
        val userService = TestUserServiceBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withEnabledUser(userWithBothIds)
          .build
        val newAzureB2CId = "not a valid azureB2CId"
        val request = AdminUpdateUserRequest(Some(AzureB2CId(newAzureB2CId)), None, None, None)

        // Act
        val error = intercept[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.updateUserCrud(userWithBothIds.id, request, samRequestContext))
        }

        // Assert
        assert(error.errorReport.statusCode.value equals StatusCodes.BadRequest, "Updating a user with an invalid azureB2CId should have a Bad Request status")
        assert(error.errorReport.message equals "invalid user update", "Updating a user with an invalid azureB2CId should send back a invalid update message")
        assert(
          error.errorReport.causes.head.message equals s"invalid azureB2CId [$newAzureB2CId]",
          "Updating a user with an invalid azureB2CId should send back the correct cause"
        )
      }
    }
  }

  describe("with an null azureB2CId") {
    it("should successfully null the azureB2CId in the database") {
      // Arrange
      val userWithBothIds = genWorkbenchUserBoth.sample.get
      val directoryDAO = new MockDirectoryDAO
      val cloudExtensions = StatefulMockCloudExtensionsBuilder(directoryDAO).build
      val tosService = mock[TosService]
      val userService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

      val updateRequest = AdminUpdateUserRequest(Some(AzureB2CId("null")), None, None, None)

      TestSupport.runAndWait(directoryDAO.createUser(userWithBothIds, samRequestContext))

      // Act
      val response = runAndWait(userService.updateUserCrud(userWithBothIds.id, updateRequest, samRequestContext))

      // Assert
      assert(response.nonEmpty, "Updating a user with an empty azureB2CId should return an object")
      assert(
        response.get equals userWithBothIds.copy(azureB2CId = None),
        "Updating a user with an empty azureB2CId should return the corresponding user after update."
      )
    }
  }

  describe("an existing user's azureB2CId, who has a null googleSubjectId") {
    describe("with an null azureB2CId") {
      it("should throw an exception") {
        // Arrange
        val azureUser = genWorkbenchUserAzure.sample.get
        val directoryDAO = new MockDirectoryDAO
        val cloudExtensions = StatefulMockCloudExtensionsBuilder(directoryDAO).build
        val tosService = mock[TosService]
        val userService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

        val updateRequest = AdminUpdateUserRequest(Some(AzureB2CId("null")), None, None, None)

        TestSupport.runAndWait(directoryDAO.createUser(azureUser, samRequestContext))

        // Act
        val error = intercept[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.updateUserCrud(azureUser.id, updateRequest, samRequestContext))
        }

        // Assert
        assert(
          error.errorReport.statusCode.value equals StatusCodes.BadRequest,
          "Nulling a user's azureB2CId when the user has no googleSubjectId should have a Bad Request status"
        )
        assert(
          error.errorReport.message equals "Cannot null a user's azureB2CId when the user has no googleSubjectId",
          "Nulling a user's azureB2CId when the user has no googleSubjectId should send back a invalid update message"
        )
      }
    }
  }

  describe("an existing user's googlesSubjectId, who has a null azureB2CId") {
    describe("with an null googleSubjectId") {
      it("should throw an exception") {
        // Arrange
        val googleUser = genWorkbenchUserGoogle.sample.get
        val directoryDAO = new MockDirectoryDAO
        val cloudExtensions = StatefulMockCloudExtensionsBuilder(directoryDAO).build
        val tosService = mock[TosService]
        val userService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

        val updateRequest = AdminUpdateUserRequest(None, Some(GoogleSubjectId("null")), None, None)

        TestSupport.runAndWait(directoryDAO.createUser(googleUser, samRequestContext))

        // Act
        val error = intercept[WorkbenchExceptionWithErrorReport] {
          runAndWait(userService.updateUserCrud(googleUser.id, updateRequest, samRequestContext))
        }

        // Assert
        assert(
          error.errorReport.statusCode.value equals StatusCodes.BadRequest,
          "Nulling a user's azureB2CId when the user has no googleSubjectId should have a Bad Request status"
        )
        assert(
          error.errorReport.message equals "Cannot null a user's azureB2CId when the user has no googleSubjectId",
          "Nulling a user's azureB2CId when the user has no googleSubjectId should send back a invalid update message"
        )
      }
    }
  }
}
