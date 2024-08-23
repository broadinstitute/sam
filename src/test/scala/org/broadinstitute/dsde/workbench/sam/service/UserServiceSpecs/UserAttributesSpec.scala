package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.Generator.genWorkbenchUserBoth
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDaoBuilder}
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.model.api.{SamUserAttributes, SamUserAttributesRequest, SamUserRegistrationRequest}
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

  val firstName: Option[String] = Some("firstName")
  val lastName: Option[String] = Some("lastName")
  val organization: Option[String] = Some("organization")
  val contactEmail: Option[String] = Some("contactEmail")
  val title: Option[String] = Some("title")
  val department: Option[String] = Some("department")
  val interestInTerra: Option[List[String]] = Some(List("interestInTerra"))
  val programLocationCity: Option[String] = Some("programLocationCity")
  val programLocationState: Option[String] = Some("programLocationState")
  val programLocationCountry: Option[String] = Some("programLocationCountry")
  val researchArea: Option[List[String]] = Some(List("researchArea"))
  val additionalAttributes: Option[String] = Some("""{"additionalAttributes": "foo"}""")
  val createdAt: Option[Instant] = Some(Instant.parse("2022-01-01T00:00:00Z"))
  val updatedAt: Option[Instant] = Some(Instant.parse("2023-01-01T00:00:00Z"))
  val newUpdatedAt: Option[Instant] = Some(Instant.parse("2024-01-01T00:00:00Z"))

  describe("user attributes") {
    val user = genWorkbenchUserBoth.sample.get.copy(enabled = true)

    it("should be retrieved for a user") {
      // Arrange
      val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup)
        .withUserAttributes(
          SamUserAttributes(
            user.id,
            marketingConsent = true,
            firstName,
            lastName,
            organization,
            contactEmail,
            title,
            department,
            interestInTerra,
            programLocationCity,
            programLocationState,
            programLocationCountry,
            researchArea,
            additionalAttributes,
            createdAt,
            updatedAt
          )
        )
        .build
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

      // Act
      val response = runAndWait(userService.getUserAttributes(user.id, samRequestContext))

      // Assert
      response should be(
        Some(
          SamUserAttributes(
            user.id,
            marketingConsent = true,
            firstName,
            lastName,
            organization,
            contactEmail,
            title,
            department,
            interestInTerra,
            programLocationCity,
            programLocationState,
            programLocationCountry,
            researchArea,
            additionalAttributes,
            createdAt,
            updatedAt
          )
        )
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
        firstName,
        lastName,
        organization,
        contactEmail,
        title,
        department,
        interestInTerra,
        None,
        None,
        None,
        None,
        None
      )
      // Act
      val response = runAndWait(userService.setUserAttributesFromRequest(user.id, userAttributesRequest, samRequestContext))

      // Assert
      val userAttributes = SamUserAttributes(
        user.id,
        marketingConsent = true,
        firstName,
        lastName,
        organization,
        contactEmail,
        title,
        department,
        interestInTerra,
        programLocationCity = None,
        programLocationState = None,
        programLocationCountry = None,
        researchArea = None,
        additionalAttributes = None,
        response.createdAt,
        response.updatedAt
      )

      response.userId should be(user.id)
      response.marketingConsent should be(userAttributes.marketingConsent)
      response.firstName should be(userAttributes.firstName)
      response.lastName should be(userAttributes.lastName)
      response.organization should be(userAttributes.organization)
      response.contactEmail should be(userAttributes.contactEmail)
      response.title should be(userAttributes.title)
      response.department should be(userAttributes.department)
      response.interestInTerra should be(userAttributes.interestInTerra)
      response.programLocationCity should be(userAttributes.programLocationCity)
      response.programLocationState should be(userAttributes.programLocationState)
      response.programLocationCountry should be(userAttributes.programLocationCountry)
      response.researchArea should be(userAttributes.researchArea)
      response.additionalAttributes should be(userAttributes.additionalAttributes)
      response.createdAt.get should beAround(userAttributes.createdAt.get)
      response.updatedAt.get should beAround(userAttributes.updatedAt.get)

      verify(directoryDAO).getUserAttributes(eqTo(user.id), any[SamRequestContext])
      verify(directoryDAO).setUserAttributes(eqTo(SamUserAttributes(
        userAttributes.userId,
        userAttributes.marketingConsent,
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
        userAttributes.additionalAttributes,
        userAttributes.createdAt,
        userAttributes.updatedAt)
      ), any[SamRequestContext])
    }

    it("updates existing user attributes") {
      // Arrange
      val userAttributes = SamUserAttributes(
        user.id,
        marketingConsent = true,
        firstName,
        lastName,
        organization,
        contactEmail,
        title,
        department,
        interestInTerra,
        programLocationCity,
        programLocationState,
        programLocationCountry,
        researchArea,
        additionalAttributes,
        createdAt,
        updatedAt
      )
      val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup)
        .withUserAttributes(userAttributes)
        .build
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

      val userAttributesRequest = SamUserAttributesRequest(
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
      val response = runAndWait(userService.setUserAttributesFromRequest(user.id, userAttributesRequest, samRequestContext))

      // Assert
      val updatedUserAttributes = userAttributes.copy(
        marketingConsent = false,
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

      response should be(updatedUserAttributes)
      response.createdAt should be(createdAt)
      response.updatedAt should be(updatedAt)
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
      val newUserAttributes = SamUserAttributes(
        user.id,
        marketingConsent = true,
        firstName,
        lastName,
        organization,
        contactEmail,
        title,
        department,
        interestInTerra,
        programLocationCity,
        programLocationState,
        programLocationCountry,
        researchArea,
        additionalAttributes,
        createdAt,
        updatedAt
      )
      val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).withUserAttributes(newUserAttributes).build
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
                marketingConsent = Some(true),
                firstName,
                lastName,
                organization,
                contactEmail,
                title,
                department,
                interestInTerra,
                programLocationCity,
                programLocationState,
                programLocationCountry,
                researchArea,
                additionalAttributes)
            )
          ),
          samRequestContext
        )
      )

      // Assert
      verify(directoryDAO).setUserAttributes(eqTo(newUserAttributes), any[SamRequestContext])
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
