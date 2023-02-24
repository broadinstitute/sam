package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.Generator.genWorkbenchUserBoth
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, TestUserServiceBuilder}

import scala.concurrent.ExecutionContextExecutor

class DeleteUserSpec extends UserServiceTestTraits {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  describe("When deleting") {
    describe("an existing user") {
      it("should be successful") {
        // Arrange
        val userWithBothIds = genWorkbenchUserBoth.sample.get
        val userService = TestUserServiceBuilder()
          .withAllUsersGroup(allUsersGroup)
          .withEnabledUser(userWithBothIds)
          .build

        // Act
        runAndWait(userService.deleteUser(userWithBothIds.id, samRequestContext))

        // Assert
        assert(true, "Deleting the user should succeed without throwing an exception.")
      }
    }
    describe("user that does not exist") {
      it("should be successful") {
        // Arrange
        val userWithBothIds = genWorkbenchUserBoth.sample.get
        val userService = TestUserServiceBuilder()
          .withAllUsersGroup(allUsersGroup)
          .build

        // Act
        runAndWait(userService.deleteUser(userWithBothIds.id, samRequestContext))

        // Assert
        assert(true, "Deleting the user should succeed without throwing an exception.")
      }
    }
  }



}
