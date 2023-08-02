package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.Generator.genWorkbenchUserBoth
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.model.api.AdminUpdateUserRequest
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, TestUserServiceBuilder}

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
          val request = AdminUpdateUserRequest(None, Some(WorkbenchEmail(email)), None)
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
          val request = AdminUpdateUserRequest(None, Some(WorkbenchEmail(email)), None)

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
        val request = AdminUpdateUserRequest(None, Some(WorkbenchEmail("goodemail@gmail.com")), None)
        // Act
        val response = runAndWait(userService.updateUserCrud(userWithBothIds.id, request, samRequestContext))

        // Assert
        assert(response.isEmpty, "Updating a nonexistent user should not find a user that it updated")
      }
    }
  }

}
