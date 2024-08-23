package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.Generator.genWorkbenchUserBoth
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDaoBuilder}
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.model.api.{SamUser, SamUserAttributes, SamUserAttributesRequest, SamUserRegistrationRequest}
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, MockCloudExtensionsBuilder, MockTosServiceBuilder, TosService, UserService}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchersSugar.{any, eqTo}
import org.mockito.Mockito.verify

import java.time.Instant
import scala.concurrent.ExecutionContextExecutor
import org.broadinstitute.dsde.workbench.sam.matchers.TimeMatchers

class UserAttributesSpec extends UserServiceTestTraits with TimeMatchers {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  val cloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
  val tosService: TosService = MockTosServiceBuilder().withAllAccepted().build

  val user: SamUser = genWorkbenchUserBoth.sample.get.copy(enabled = true)
  val userAttributes: SamUserAttributes = new SamUserAttributes(
    user.id,
    marketingConsent = true,
    firstName = Some("firstName"),
    lastName = Some("lastName"),
    organization = Some("organization"),
    contactEmail = Some("contactEmail"),
    title = Some("title"),
    department = Some("department"),
    interestInTerra = Some(List("interestInTerra")),
    programLocationCity = Some("programLocationCity"),
    programLocationState = Some("programLocationState"),
    programLocationCountry = Some("programLocationCountry"),
    researchArea = Some(List("researchArea")),
    additionalAttributes = Some("""{"additionalAttributes": "foo"}"""),
    createdAt = Some(Instant.parse("2022-01-01T00:00:00Z")),
    updatedAt = Some(Instant.parse("2023-01-01T00:00:00Z"))
  )
  val newUpdatedAt: Option[Instant] = Some(Instant.parse("2024-01-01T00:00:00Z"))

  describe("user attributes") {

    it("should be retrieved for a user") {
      // Arrange
      val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup)
        .withUserAttributes(userAttributes)
        .build
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

      // Act
      val response = runAndWait(userService.getUserAttributes(user.id, samRequestContext))

      // Assert
      response should be(
        Some(userAttributes)
      )
      verify(directoryDAO).getUserAttributes(eqTo(user.id), any[SamRequestContext])
    }

    it("should set user attributes for a new user") {
      // Arrange
      val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)
      val userAttributesRequest = SamUserAttributesRequest(
        user.id,
        marketingConsent = Some(true),
        userAttributes.firstName,
        userAttributes.lastName,
        userAttributes.organization,
        userAttributes.contactEmail,
        userAttributes.title,
        userAttributes.department,
        userAttributes.interestInTerra,
        None,
        None,
        None,
        None,
        None
      )
      // Act
      val response = runAndWait(userService.setUserAttributesFromRequest(user.id, userAttributesRequest, samRequestContext))

      // Assert
      val newUserAttributes = SamUserAttributes(
        user.id,
        marketingConsent = true,
        userAttributesRequest.firstName,
        userAttributesRequest.lastName,
        userAttributesRequest.organization,
        userAttributesRequest.contactEmail,
        userAttributesRequest.title,
        userAttributesRequest.department,
        userAttributesRequest.interestInTerra,
        programLocationCity = None,
        programLocationState = None,
        programLocationCountry = None,
        researchArea = None,
        additionalAttributes = None,
        response.createdAt,
        response.updatedAt
      )

      response.userId should be(user.id)
      response.marketingConsent should be(newUserAttributes.marketingConsent)
      response.firstName should be(newUserAttributes.firstName)
      response.lastName should be(newUserAttributes.lastName)
      response.organization should be(newUserAttributes.organization)
      response.contactEmail should be(newUserAttributes.contactEmail)
      response.title should be(newUserAttributes.title)
      response.department should be(newUserAttributes.department)
      response.interestInTerra should be(newUserAttributes.interestInTerra)
      response.programLocationCity should be(newUserAttributes.programLocationCity)
      response.programLocationState should be(newUserAttributes.programLocationState)
      response.programLocationCountry should be(newUserAttributes.programLocationCountry)
      response.researchArea should be(newUserAttributes.researchArea)
      response.additionalAttributes should be(newUserAttributes.additionalAttributes)
      response.createdAt.get should beAround(newUserAttributes.createdAt.get)
      response.updatedAt.get should beAround(newUserAttributes.updatedAt.get)

      verify(directoryDAO).getUserAttributes(eqTo(user.id), any[SamRequestContext])
      verify(directoryDAO).setUserAttributes(eqTo(SamUserAttributes(
        newUserAttributes.userId,
        newUserAttributes.marketingConsent,
        newUserAttributes.firstName,
        newUserAttributes.lastName,
        newUserAttributes.organization,
        newUserAttributes.contactEmail,
        newUserAttributes.title,
        newUserAttributes.department,
        newUserAttributes.interestInTerra,
        newUserAttributes.programLocationCity,
        newUserAttributes.programLocationState,
        newUserAttributes.programLocationCountry,
        newUserAttributes.researchArea,
        newUserAttributes.additionalAttributes,
        newUserAttributes.createdAt,
        newUserAttributes.updatedAt)
      ), any[SamRequestContext])
    }

    it("updates existing user attributes") {
      // Arrange
      val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup)
        .withUserAttributes(userAttributes)
        .build
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

      val updateUserAttributesRequest = SamUserAttributesRequest(
        user.id,
        marketingConsent = Some(false),
        firstName = Some("newFirstName"),
        lastName = Some("newLastName"),
        organization = Some("newOrganization"),
        contactEmail = Some("newContactEmail"),
        title = Some("newTitle"),
        department = Some("newDepartment"),
        interestInTerra = Some(List("newInterestInTerra")),
        programLocationCity = Some("newProgramLocationCity"),
        programLocationState = Some("newProgramLocationState"),
        programLocationCountry = Some("newProgramLocationCountry"),
        researchArea = Some(List("newResearchArea")),
        additionalAttributes = Some("""{"additionalAttributes": "bar"}""")
      )
      // Act
      val response = runAndWait(userService.setUserAttributesFromRequest(user.id, updateUserAttributesRequest, samRequestContext))

      // Assert
      val updatedUserAttributes = new SamUserAttributes(
        response.userId,
        response.marketingConsent,
        response.firstName,
        response.lastName,
        response.organization,
        response.contactEmail,
        response.title,
        response.department,
        response.interestInTerra,
        response.programLocationCity,
        response.programLocationState,
        response.programLocationCountry,
        response.researchArea,
        response.additionalAttributes,
        response.createdAt,
        response.updatedAt
      )

      response should be(updatedUserAttributes)
      verify(directoryDAO).getUserAttributes(eqTo(user.id), any[SamRequestContext])
      verify(directoryDAO).setUserAttributes(eqTo(updatedUserAttributes), any[SamRequestContext])
    }

    it("sets user attributes when a new user is registered without a request body") {
      // Arrange
      val newUserAttributes = new SamUserAttributes(
        user.id,
        false,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        Some(Instant.parse("2022-01-01T00:00:00Z")),
        Some(Instant.parse("2023-01-01T00:00:00Z")))
      val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).withUserAttributes(newUserAttributes).build
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

      // Act
      runAndWait(userService.createUser(user, samRequestContext))

      // Assert
      verify(directoryDAO).setUserAttributes(eqTo(SamUserAttributes(
        newUserAttributes.userId,
        newUserAttributes.marketingConsent,
        newUserAttributes.firstName,
        newUserAttributes.lastName,
        newUserAttributes.organization,
        newUserAttributes.contactEmail,
        newUserAttributes.title,
        newUserAttributes.department,
        newUserAttributes.interestInTerra,
        newUserAttributes.programLocationCity,
        newUserAttributes.programLocationState,
        newUserAttributes.programLocationCountry,
        newUserAttributes.researchArea,
        newUserAttributes.additionalAttributes,
        newUserAttributes.createdAt,
        newUserAttributes.updatedAt)), any[SamRequestContext])
    }

    it("sets user attributes when a new user is registered with a request body") {
      // Arrange
      val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).withUserAttributes(userAttributes).build
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

      // Act
      runAndWait(
        userService.createUser(
          user,
          Some(
            SamUserRegistrationRequest(
              true,
              SamUserAttributesRequest(
                user.id,
                Some(userAttributes.marketingConsent),
                userAttributes.firstName,
                userAttributes.lastName,
                userAttributes.organization,
                userAttributes.contactEmail,
                userAttributes.title,
                userAttributes.department,
                userAttributes.interestInTerra,
                userAttributes.programLocationCity,
                userAttributes.programLocationState,
                userAttributes.programLocationCountry,
                userAttributes.researchArea,
                userAttributes.additionalAttributes)
            )
          ),
          samRequestContext
        )
      )

      // Assert
      verify(directoryDAO).setUserAttributes(eqTo(userAttributes), any[SamRequestContext])
    }

    it("sets user attributes when a new user is invited") {
      // Arrange
      val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

      // Act
      val response = runAndWait(userService.inviteUser(user.email, samRequestContext))

      // Assert
      val userAttributes = SamUserAttributes(
        response.userSubjectId,
        marketingConsent = false,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        None
      )
      verify(directoryDAO).setUserAttributes(eqTo(userAttributes), any[SamRequestContext])
    }
    it("sets userAttributes.createdAt and userAttributes.updatedAt when a new user is created"){}
    it("updates userAttributes.updatedAt when a new user is created"){}


  }
}
