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

  val defaultUser: SamUser = Generator.genWorkbenchUserGoogle.sample.get

  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
  val cloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build

  describe("getSamUserCombinedState") {
    it("should get the user combined state of the calling user") {
      // Arrange
      val userAttributes = SamUserAttributes(defaultUser.id, marketingConsent = true)
      lenient()
        .doReturn(IO.pure(Option(userAttributes)))
        .when(directoryDAO)
        .getUserAttributes(ArgumentMatchers.eq(defaultUser.id), any[SamRequestContext])

      // --- resource service
      val favoriteResources = Set(FullyQualifiedResourceId(ResourceTypeName("workspaceType"), ResourceId("workspaceName")))
      val enterpriseFeature = FilteredResourceFlat(
        resourceType = ResourceTypeName("enterprise-feature"),
        resourceId = ResourceId("enterprise-feature"),
        policies = Set.empty,
        roles = Set(ResourceRoleName("user")),
        actions = Set.empty,
        authDomainGroups = Set.empty,
        missingAuthDomainGroups = Set.empty
      )
      val filteredResourcesFlat = FilteredResourcesFlat(Set(enterpriseFeature))

      val mockResourceService = mock[ResourceService]

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

      // --- tos service
      val tosService: TosService = MockTosServiceBuilder()
        .withAcceptedStateForUser(defaultUser, isAccepted = true)
        .build
      val termsOfServiceDetails = TermsOfServiceDetails(Option("v1"), Option(Instant.now()), permitsSystemUsage = true, isCurrentVersion = true)
      lenient()
        .doReturn(IO.pure(Option(termsOfServiceDetails)))
        .when(tosService)
        .getTermsOfServiceDetailsForUser(ArgumentMatchers.eq(defaultUser.id), any[SamRequestContext])

      // -- group counts
      lenient()
        .doReturn(IO.pure(7))
        .when(directoryDAO)
        .countDirectSynchronizedGroupMemberships(any[SamUser], any[SamRequestContext])
      lenient()
        .doReturn(IO.pure(42))
        .when(directoryDAO)
        .countIndirectSynchronizedGroupMemberships(any[SamUser], any[SamRequestContext])

      val userCombinedStateResponse = SamUserCombinedStateResponse(
        defaultUser,
        SamUserAllowances(enabled = true, termsOfService = true),
        Option(SamUserAttributes(defaultUser.id, marketingConsent = true)),
        TermsOfServiceDetails(Option("v1"), Option(Instant.now()), permitsSystemUsage = true, isCurrentVersion = true),
        GroupMembershipCounts(7, 42),
        Map("enterpriseFeatures" -> FilteredResourcesFlat(Set(enterpriseFeature)).toJson),
        favoriteResources
      )

      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

      val testUser = defaultUser.copy(enabled = true)

      val response = runAndWait(userService.getSamUserCombinedState(testUser, samRequestContext, mockResourceService))

      response.samUser should be(testUser)
      response.allowances should be(userCombinedStateResponse.allowances)
      response.attributes should be(userCombinedStateResponse.attributes)
      response.termsOfServiceDetails.acceptedOn.get should beAround(userCombinedStateResponse.termsOfServiceDetails.acceptedOn.get)
      response.termsOfServiceDetails.isCurrentVersion should be(userCombinedStateResponse.termsOfServiceDetails.isCurrentVersion)
      response.termsOfServiceDetails.permitsSystemUsage should be(userCombinedStateResponse.termsOfServiceDetails.permitsSystemUsage)
      response.termsOfServiceDetails.latestAcceptedVersion should be(userCombinedStateResponse.termsOfServiceDetails.latestAcceptedVersion)
      response.additionalDetails should be(Map("enterpriseFeatures" -> filteredResourcesFlat.toJson))
      response.favoriteResources should be(favoriteResources)
      response.groupMembershipCounts.directSynchronized should be(7)
      response.groupMembershipCounts.totalSynchronized should be(42)
    }
    it("return null attributes if the user has no attributes") {
      // Arrange
      lenient()
        .doReturn(IO.pure(None))
        .when(directoryDAO)
        .getUserAttributes(ArgumentMatchers.eq(defaultUser.id), any[SamRequestContext])

      // --- resource service
      val favoriteResources = Set(FullyQualifiedResourceId(ResourceTypeName("workspaceType"), ResourceId("workspaceName")))
      val enterpriseFeature = FilteredResourceFlat(
        resourceType = ResourceTypeName("enterprise-feature"),
        resourceId = ResourceId("enterprise-feature"),
        policies = Set.empty,
        roles = Set(ResourceRoleName("user")),
        actions = Set.empty,
        authDomainGroups = Set.empty,
        missingAuthDomainGroups = Set.empty
      )
      val filteredResourcesFlat = FilteredResourcesFlat(Set(enterpriseFeature))

      val mockResourceService = mock[ResourceService]

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

      // --- tos service
      val tosService: TosService = MockTosServiceBuilder()
        .withAcceptedStateForUser(defaultUser, isAccepted = true)
        .build
      val termsOfServiceDetails = TermsOfServiceDetails(Option("v1"), Option(Instant.now()), permitsSystemUsage = true, isCurrentVersion = true)
      lenient()
        .doReturn(IO.pure(Option(termsOfServiceDetails)))
        .when(tosService)
        .getTermsOfServiceDetailsForUser(ArgumentMatchers.eq(defaultUser.id), any[SamRequestContext])

      // -- group counts
      lenient()
        .doReturn(IO.pure(7))
        .when(directoryDAO)
        .countDirectSynchronizedGroupMemberships(any[SamUser], any[SamRequestContext])
      lenient()
        .doReturn(IO.pure(42))
        .when(directoryDAO)
        .countIndirectSynchronizedGroupMemberships(any[SamUser], any[SamRequestContext])

      val userCombinedStateResponse = SamUserCombinedStateResponse(
        defaultUser,
        SamUserAllowances(enabled = true, termsOfService = true),
        None,
        TermsOfServiceDetails(Option("v1"), Option(Instant.now()), permitsSystemUsage = true, isCurrentVersion = true),
        GroupMembershipCounts(7, 42),
        Map("enterpriseFeatures" -> FilteredResourcesFlat(Set(enterpriseFeature)).toJson),
        favoriteResources
      )

      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

      val testUser = defaultUser.copy(enabled = true)

      val response = runAndWait(userService.getSamUserCombinedState(testUser, samRequestContext, mockResourceService))

      response.samUser should be(testUser)
      response.allowances should be(userCombinedStateResponse.allowances)
      response.attributes should be(None)
      response.termsOfServiceDetails.acceptedOn.get should beAround(userCombinedStateResponse.termsOfServiceDetails.acceptedOn.get)
      response.termsOfServiceDetails.isCurrentVersion should be(userCombinedStateResponse.termsOfServiceDetails.isCurrentVersion)
      response.termsOfServiceDetails.permitsSystemUsage should be(userCombinedStateResponse.termsOfServiceDetails.permitsSystemUsage)
      response.termsOfServiceDetails.latestAcceptedVersion should be(userCombinedStateResponse.termsOfServiceDetails.latestAcceptedVersion)
      response.additionalDetails should be(Map("enterpriseFeatures" -> filteredResourcesFlat.toJson))
      response.favoriteResources should be(favoriteResources)
      response.groupMembershipCounts.directSynchronized should be(7)
      response.groupMembershipCounts.totalSynchronized should be(42)
    }
    it("return falsy terms of service if the user has no tos history") {
      // Arrange
      val userAttributes = SamUserAttributes(defaultUser.id, marketingConsent = true)
      lenient()
        .doReturn(IO.pure(Option(userAttributes)))
        .when(directoryDAO)
        .getUserAttributes(ArgumentMatchers.eq(defaultUser.id), any[SamRequestContext])

      // --- resource service
      val favoriteResources = Set(FullyQualifiedResourceId(ResourceTypeName("workspaceType"), ResourceId("workspaceName")))
      val enterpriseFeature = FilteredResourceFlat(
        resourceType = ResourceTypeName("enterprise-feature"),
        resourceId = ResourceId("enterprise-feature"),
        policies = Set.empty,
        roles = Set(ResourceRoleName("user")),
        actions = Set.empty,
        authDomainGroups = Set.empty,
        missingAuthDomainGroups = Set.empty
      )
      val filteredResourcesFlat = FilteredResourcesFlat(Set(enterpriseFeature))

      val mockResourceService = mock[ResourceService]

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

      // --- tos service
      val tosService: TosService = MockTosServiceBuilder().build
      val termsOfServiceDetails = TermsOfServiceDetails(None, None, false, isCurrentVersion = false)
      lenient()
        .doReturn(IO.pure(Option(termsOfServiceDetails)))
        .when(tosService)
        .getTermsOfServiceDetailsForUser(ArgumentMatchers.eq(defaultUser.id), any[SamRequestContext])

      // -- group counts
      lenient()
        .doReturn(IO.pure(7))
        .when(directoryDAO)
        .countDirectSynchronizedGroupMemberships(any[SamUser], any[SamRequestContext])
      lenient()
        .doReturn(IO.pure(42))
        .when(directoryDAO)
        .countIndirectSynchronizedGroupMemberships(any[SamUser], any[SamRequestContext])

      val userCombinedStateResponse = SamUserCombinedStateResponse(
        defaultUser,
        SamUserAllowances(enabled = true, termsOfService = false),
        Option(SamUserAttributes(defaultUser.id, marketingConsent = true)),
        TermsOfServiceDetails(None, None, permitsSystemUsage = false, isCurrentVersion = false),
        GroupMembershipCounts(7, 42),
        Map("enterpriseFeatures" -> FilteredResourcesFlat(Set(enterpriseFeature)).toJson),
        favoriteResources
      )

      val userService: UserService = new UserService(directoryDAO, cloudExtensions, Seq.empty, tosService)

      val testUser = defaultUser.copy(enabled = true)

      val response = runAndWait(userService.getSamUserCombinedState(testUser, samRequestContext, mockResourceService))

      response.samUser should be(testUser)
      response.allowances should be(userCombinedStateResponse.allowances)
      response.attributes should be(userCombinedStateResponse.attributes)
      response.termsOfServiceDetails.acceptedOn should be(None)
      response.termsOfServiceDetails.isCurrentVersion should be(userCombinedStateResponse.termsOfServiceDetails.isCurrentVersion)
      response.termsOfServiceDetails.permitsSystemUsage should be(userCombinedStateResponse.termsOfServiceDetails.permitsSystemUsage)
      response.termsOfServiceDetails.latestAcceptedVersion should be(userCombinedStateResponse.termsOfServiceDetails.latestAcceptedVersion)
      response.additionalDetails should be(Map("enterpriseFeatures" -> filteredResourcesFlat.toJson))
      response.favoriteResources should be(favoriteResources)
      response.groupMembershipCounts.directSynchronized should be(7)
      response.groupMembershipCounts.totalSynchronized should be(42)
    }
  }
}
