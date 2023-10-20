package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.Generator.genWorkbenchUserBoth
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDaoBuilder}
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.model.api.{SamUserAttributes, SamUserAttributesRequest}
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, MockCloudExtensionsBuilder, MockTosServiceBuilder, TosService, UserService}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchersSugar.{any, eqTo}
import org.mockito.Mockito.verify

import scala.concurrent.ExecutionContextExecutor

class UserAttributesSpec extends UserServiceTestTraits {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  val cloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
  val tosService: TosService = MockTosServiceBuilder().withAllAccepted().build

  describe("user attributes") {
    val user = genWorkbenchUserBoth.sample.get.copy(enabled = true)

    it("should be retrieved for a user") {
      // Arrange
      val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup)
        .withUserAttributes(SamUserAttributes(user.id, marketingConsent = true))
        .build
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)
      // Act
      val response = runAndWait(userService.getUserAttributes(user.id, samRequestContext))

      // Assert
      response should be(Some(SamUserAttributes(user.id, marketingConsent = true)))
      verify(directoryDAO).getUserAttributes(eqTo(user.id), any[SamRequestContext])
    }

    it("should set user attributes for a new user") {
      // Arrange
      val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)
      val userAttributesRequest = SamUserAttributesRequest(marketingConsent = Some(true))
      // Act
      val response = runAndWait(userService.setUserAttributesFromRequest(user.id, userAttributesRequest, samRequestContext))

      // Assert
      val userAttributes = SamUserAttributes(user.id, marketingConsent = true)

      response should be(userAttributes)
      verify(directoryDAO).getUserAttributes(eqTo(user.id), any[SamRequestContext])
      verify(directoryDAO).setUserAttributes(eqTo(userAttributes), any[SamRequestContext])
    }

    it("update existing user attributes") {
      // Arrange
      val userAttributes = SamUserAttributes(user.id, marketingConsent = true)
      val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup)
        .withUserAttributes(userAttributes)
        .build
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)
      val userAttributesRequest = SamUserAttributesRequest(marketingConsent = Some(false))
      // Act
      val response = runAndWait(userService.setUserAttributesFromRequest(user.id, userAttributesRequest, samRequestContext))

      // Assert
      val updatedUserAttributes = userAttributes.copy(marketingConsent = false)

      response should be(updatedUserAttributes)
      verify(directoryDAO).getUserAttributes(eqTo(user.id), any[SamRequestContext])
      verify(directoryDAO).setUserAttributes(eqTo(updatedUserAttributes), any[SamRequestContext])
    }

    it("sets user attributes when a new user is registered") {
      // Arrange
      val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

      // Act
      runAndWait(userService.createUser(user, samRequestContext))

      // Assert
      val userAttributes = SamUserAttributes(user.id, marketingConsent = true)
      verify(directoryDAO).setUserAttributes(eqTo(userAttributes), any[SamRequestContext])
    }

    it("sets user attributes when a new user is invited") {
      // Arrange
      val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

      // Act
      val response = runAndWait(userService.inviteUser(user.email, samRequestContext))

      // Assert
      val userAttributes = SamUserAttributes(response.userSubjectId, marketingConsent = true)
      verify(directoryDAO).setUserAttributes(eqTo(userAttributes), any[SamRequestContext])
    }

  }
}
