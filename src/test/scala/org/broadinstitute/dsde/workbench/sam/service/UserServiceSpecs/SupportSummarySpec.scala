package org.broadinstitute.dsde.workbench.sam.service.UserServiceSpecs

import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.Generator
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDaoBuilder}
import org.broadinstitute.dsde.workbench.sam.matchers.TimeMatchers
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchersSugar.any
import org.mockito.Mockito.lenient
import spray.json.enrichAny

import java.time.Instant
import scala.concurrent.ExecutionContextExecutor

class SupportSummarySpec extends UserServiceTestTraits with TimeMatchers {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  // exemplar data for tests
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  val nowInstant: Instant = Instant.now()

  val testUser: SamUser = Generator.genWorkbenchUserGoogle.sample.get.copy(enabled = true)

  val userAttributes: SamUserAttributes = SamUserAttributes(testUser.id, marketingConsent = true)

  val favoriteResources: Set[FullyQualifiedResourceId] = Set(FullyQualifiedResourceId(ResourceTypeName("workspaceType"), ResourceId("workspaceName")))
  val enterpriseFeature: FilteredResourceFlat = FilteredResourceFlat(
    resourceType = ResourceTypeName("enterprise-feature"),
    resourceId = ResourceId("enterprise-feature"),
    policies = Set.empty,
    roles = Set(ResourceRoleName("user")),
    actions = Set.empty,
    authDomainGroups = Set.empty,
    missingAuthDomainGroups = Set.empty
  )
  val filteredResourcesFlat: FilteredResourcesFlat = FilteredResourcesFlat(Set(enterpriseFeature))

  val termsOfServiceDetails: TermsOfServiceDetails = TermsOfServiceDetails(Option("v1"), Option(nowInstant), permitsSystemUsage = true, isCurrentVersion = true)

  // mock services/DAOs
  val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
  val cloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
  val mockResourceService: ResourceService = mock[ResourceService]
  val tosService: TosService = MockTosServiceBuilder()
    .withAcceptedStateForUser(testUser, isAccepted = true)
    .build

  // configure mocks
  def setupMocks(): Unit = {
    // attributes
    lenient()
      .doReturn(IO.pure(Option(userAttributes)))
      .when(directoryDAO)
      .getUserAttributes(ArgumentMatchers.eq(testUser.id), any[SamRequestContext])

    // resource service
    lenient()
      .doReturn(IO.pure(FilteredResourcesFlat(Set(enterpriseFeature))))
      .when(mockResourceService)
      .listResourcesFlat(
        any[WorkbenchUserId],
        any[Set[ResourceTypeName]],
        any[Set[AccessPolicyName]],
        any[Set[ResourceRoleName]],
        any[Set[ResourceAction]],
        any[Boolean],
        any[SamRequestContext]
      )
    lenient()
      .doReturn(IO.pure(favoriteResources))
      .when(mockResourceService)
      .getUserFavoriteResources(any[WorkbenchUserId], any[SamRequestContext])

    // -- group counts
    lenient()
      .doReturn(IO.pure(7))
      .when(directoryDAO)
      .countDirectSynchronizedGroupMemberships(any[SamUser], any[SamRequestContext])
    lenient()
      .doReturn(IO.pure(42))
      .when(directoryDAO)
      .countIndirectSynchronizedGroupMemberships(any[SamUser], any[SamRequestContext])
    lenient()
      .doReturn(IO.pure(1234))
      .when(directoryDAO)
      .countUnsynchronizedGroupMemberships(any[SamUser], any[SamRequestContext])

    // TOS
    lenient()
      .doReturn(IO.pure(Option(termsOfServiceDetails)))
      .when(tosService)
      .getTermsOfServiceDetailsForUser(ArgumentMatchers.eq(testUser.id), any[SamRequestContext])
  }


  describe("getSamUserCombinedState") {
    it("should get the user combined state of the calling user") {
      // Arrange
      setupMocks()
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)
      val expected = SamUserCombinedStateResponse(
        testUser,
        SamUserAllowances(enabled = true, termsOfService = true),
        Option(SamUserAttributes(testUser.id, marketingConsent = true)),
        TermsOfServiceDetails(Option("v1"), Option(nowInstant), permitsSystemUsage = true, isCurrentVersion = true),
        GroupMembershipCounts(7, 42, 1234),
        Map("enterpriseFeatures" -> FilteredResourcesFlat(Set(enterpriseFeature)).toJson),
        favoriteResources
      )

      // Act
      val response = runAndWait(userService.getSamUserCombinedState(testUser, samRequestContext, mockResourceService))

      // Assert
      response.samUser should be(testUser)
      response.allowances should be(expected.allowances)
      response.attributes should be(expected.attributes)
      response.termsOfServiceDetails.acceptedOn.get should be(expected.termsOfServiceDetails.acceptedOn.get)
      response.termsOfServiceDetails.isCurrentVersion should be(expected.termsOfServiceDetails.isCurrentVersion)
      response.termsOfServiceDetails.permitsSystemUsage should be(expected.termsOfServiceDetails.permitsSystemUsage)
      response.termsOfServiceDetails.latestAcceptedVersion should be(expected.termsOfServiceDetails.latestAcceptedVersion)
      response.additionalDetails should be(Map("enterpriseFeatures" -> filteredResourcesFlat.toJson))
      response.favoriteResources should be(favoriteResources)
      response.groupMembershipCounts.directSynchronized should be(7)
      response.groupMembershipCounts.totalSynchronized should be(42)
      response.groupMembershipCounts.unsynchronized should be(1234)
    }
    it("return null attributes if the user has no attributes") {
      // Arrange
      setupMocks()
      // override attributes to return nothing
      lenient()
        .doReturn(IO.pure(None))
        .when(directoryDAO)
        .getUserAttributes(ArgumentMatchers.eq(testUser.id), any[SamRequestContext])
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)
      val expected = SamUserCombinedStateResponse(
        testUser,
        SamUserAllowances(enabled = true, termsOfService = true),
        None,
        TermsOfServiceDetails(Option("v1"), Option(nowInstant), permitsSystemUsage = true, isCurrentVersion = true),
        GroupMembershipCounts(7, 42, 1234),
        Map("enterpriseFeatures" -> FilteredResourcesFlat(Set(enterpriseFeature)).toJson),
        favoriteResources
      )

      // Act
      val response = runAndWait(userService.getSamUserCombinedState(testUser, samRequestContext, mockResourceService))

      // Assert
      response.samUser should be(testUser)
      response.allowances should be(expected.allowances)
      response.attributes should be(None)
      response.termsOfServiceDetails.acceptedOn.get should be(expected.termsOfServiceDetails.acceptedOn.get)
      response.termsOfServiceDetails.isCurrentVersion should be(expected.termsOfServiceDetails.isCurrentVersion)
      response.termsOfServiceDetails.permitsSystemUsage should be(expected.termsOfServiceDetails.permitsSystemUsage)
      response.termsOfServiceDetails.latestAcceptedVersion should be(expected.termsOfServiceDetails.latestAcceptedVersion)
      response.additionalDetails should be(Map("enterpriseFeatures" -> filteredResourcesFlat.toJson))
      response.favoriteResources should be(favoriteResources)
      response.groupMembershipCounts.directSynchronized should be(7)
      response.groupMembershipCounts.totalSynchronized should be(42)
      response.groupMembershipCounts.unsynchronized should be(1234)
    }
    it("return falsy terms of service if the user has no tos history") {
      // Arrange
      setupMocks()
      // override TOS to return no TOS history
      val tosService: TosService = MockTosServiceBuilder().build
      val termsOfServiceDetails = TermsOfServiceDetails(None, None, false, isCurrentVersion = false)
      lenient()
        .doReturn(IO.pure(Option(termsOfServiceDetails)))
        .when(tosService)
        .getTermsOfServiceDetailsForUser(ArgumentMatchers.eq(testUser.id), any[SamRequestContext])
      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)
      val expected = SamUserCombinedStateResponse(
        testUser,
        SamUserAllowances(enabled = true, termsOfService = false),
        Option(SamUserAttributes(testUser.id, marketingConsent = true)),
        TermsOfServiceDetails(None, None, permitsSystemUsage = false, isCurrentVersion = false),
        GroupMembershipCounts(7, 42, 1234),
        Map("enterpriseFeatures" -> FilteredResourcesFlat(Set(enterpriseFeature)).toJson),
        favoriteResources
      )

      // Act
      val response = runAndWait(userService.getSamUserCombinedState(testUser, samRequestContext, mockResourceService))

      // Assert
      response.samUser should be(testUser)
      response.allowances should be(expected.allowances)
      response.attributes should be(expected.attributes)
      response.termsOfServiceDetails.acceptedOn should be(None)
      response.termsOfServiceDetails.isCurrentVersion should be(expected.termsOfServiceDetails.isCurrentVersion)
      response.termsOfServiceDetails.permitsSystemUsage should be(expected.termsOfServiceDetails.permitsSystemUsage)
      response.termsOfServiceDetails.latestAcceptedVersion should be(expected.termsOfServiceDetails.latestAcceptedVersion)
      response.additionalDetails should be(Map("enterpriseFeatures" -> filteredResourcesFlat.toJson))
      response.favoriteResources should be(favoriteResources)
      response.groupMembershipCounts.directSynchronized should be(7)
      response.groupMembershipCounts.totalSynchronized should be(42)
      response.groupMembershipCounts.unsynchronized should be(1234)
    }
  }
}
