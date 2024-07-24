package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.Generator.genWorkbenchUserBoth
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, TestUserServiceBuilder}

import scala.concurrent.ExecutionContextExecutor

class GetUsersByIdsSpec extends UserServiceTestTraits {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  describe("When getting") {
    describe("users by IDs") {
      it("should be successful") {
        // Arrange
        val users = Seq.range(0, 10).map(_ => genWorkbenchUserBoth.sample.get)
        val userService = TestUserServiceBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withExistingUsers(users)
          .withEnabledUsers(users)
          .build
        // Act
        val response = runAndWait(userService.getUsersByIds(users.map(_.id), samRequestContext))

        // Assert
        assert(response.nonEmpty, "Getting users by ID should return a list of users")
        response should contain theSameElementsAs users
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
