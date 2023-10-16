package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.Generator.genWorkbenchUserBoth
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDaoBuilder}
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.model.api.SamUserAllowances
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, MockCloudExtensionsBuilder, MockTosServiceBuilder, TosService, UserService}

import scala.concurrent.ExecutionContextExecutor

class AllowedUserSpec extends UserServiceTestTraits {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
  val cloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build

  describe("an enabled user") {
    describe("who has accepted the Terms of Service") {
      val userWithBothIds = genWorkbenchUserBoth.sample.get.copy(enabled = true)
      val tosService: TosService = MockTosServiceBuilder().withAllAccepted().build
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)
      it("should be allowed to use the system") {
        // Arrange
        // Act
        val response = runAndWait(userService.getUserAllowances(userWithBothIds, samRequestContext))

        // Assert
        response should be(SamUserAllowances(allowed = true, enabledInDatabase = true, termsOfService = true))
      }
    }
    describe("who has not accepted the Terms of Service") {
      val userWithBothIds = genWorkbenchUserBoth.sample.get.copy(enabled = true)
      it("should not be allowed to use the system") {
        // Arrange
        val tosService: TosService = MockTosServiceBuilder().withNoneAccepted().build
        val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)
        // Act
        val response = runAndWait(userService.getUserAllowances(userWithBothIds, samRequestContext))

        // Assert
        response should be(SamUserAllowances(allowed = false, enabledInDatabase = true, termsOfService = false))
      }
    }
  }
  describe("a disabled user") {
    val userWithBothIds = genWorkbenchUserBoth.sample.get.copy(enabled = false)
    it("should not be able to use the system") {
      // Arrange
      val tosService: TosService = MockTosServiceBuilder().withNoneAccepted().build
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

      // Act
      val response = runAndWait(userService.getUserAllowances(userWithBothIds, samRequestContext))

      // Assert
      response should be(SamUserAllowances(allowed = false, enabledInDatabase = false, termsOfService = false))
    }
    it("should not be able to use the system even if the Terms of Service permits them to") {
      // Arrange
      val tosService: TosService = MockTosServiceBuilder().withAllAccepted().build
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

      // Act
      val response = runAndWait(userService.getUserAllowances(userWithBothIds, samRequestContext))

      // Assert
      response should be(SamUserAllowances(allowed = false, enabledInDatabase = false, termsOfService = true))
    }
  }

}
