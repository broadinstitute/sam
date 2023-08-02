package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.Generator.genWorkbenchUserBoth
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, TestUserServiceBuilder}

import scala.concurrent.ExecutionContextExecutor

class GetUserSpec extends UserServiceTestTraits {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  describe("When getting") {
    describe("an existing user") {
      it("should be successful") {
        // Arrange
        val userWithBothIds = genWorkbenchUserBoth.sample.get.copy(enabled = true)
        val userService = TestUserServiceBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withEnabledUser(userWithBothIds)
          .build
        // Act
        val response = runAndWait(userService.getUser(userWithBothIds.id, samRequestContext))

        // Assert
        assert(response.nonEmpty, "Getting a user who exists should return a user object object")
        assert(
          response.get equals userWithBothIds,
          "Getting a user who exists should return the corresponding user."
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
      // Act
      val response = runAndWait(userService.getUser(userWithBothIds.id, samRequestContext))

      // Assert
      assert(response.isEmpty, "Getting a nonexistent user should not find a user")
    }
  }

}
