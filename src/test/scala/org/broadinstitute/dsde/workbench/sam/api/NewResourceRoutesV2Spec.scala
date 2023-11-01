package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID

class NewResourceRoutesV2Spec extends AnyFlatSpec with Matchers with ScalatestRouteTest with MockitoSugar with TestSupport {
  val defaultUser: SamUser = Generator.genWorkbenchUserGoogle.sample.get
  val otherUser: SamUser = Generator.genWorkbenchUserGoogle.sample.get
  val thirdUser: SamUser = Generator.genWorkbenchUserGoogle.sample.get
  val adminGroupEmail: WorkbenchEmail = Generator.genFirecloudEmail.sample.get
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  "GET /api/resources/v2/filter" should "correctly parse query parameters" in {
    // Arrange
    val userAttributesRequest = SamUserAttributesRequest(marketingConsent = Some(false))
    val samRoutes = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUser(defaultUser)
      .withAllowedUser(defaultUser)
      .callAsNonAdminUser(Some(defaultUser))
      .build
    when(
      samRoutes.resourceService.filterResources(
        any[SamUser],
        any[Set[ResourceTypeName]],
        any[Set[AccessPolicyName]],
        any[Set[ResourceRoleName]],
        any[Set[ResourceAction]],
        any[Boolean],
        any[SamRequestContext]
      )
    ).thenReturn(
      IO.pure(
        FilteredResources(resources =
          Set(
            FilteredResource(
              ResourceTypeName(UUID.randomUUID().toString),
              ResourceId(UUID.randomUUID().toString),
              Set.empty,
              Set.empty,
              Set.empty,
              isPublic = false
            )
          )
        )
      )
    )

    // Act and Assert
    Get(s"/api/resources/v2/filter", SamUserRegistrationRequest(userAttributesRequest)) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
    verify(samRoutes.resourceService).filterResources(
      any[SamUser],
      eqTo(Set.empty),
      eqTo(Set.empty),
      eqTo(Set.empty),
      eqTo(Set.empty),
      eqTo(false),
      any[SamRequestContext]
    )

    Get(
      s"/api/resources/v2/filter?resourceTypes=fooType,barType&policies=fooPolicy&roles=fooRole,barRole,bazRole&actions=fooAction,barAction&includePublic=true",
      SamUserRegistrationRequest(userAttributesRequest)
    ) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
    verify(samRoutes.resourceService).filterResources(
      any[SamUser],
      eqTo(Set(ResourceTypeName("fooType"), ResourceTypeName("barType"))),
      eqTo(Set(AccessPolicyName("fooPolicy"))),
      eqTo(Set(ResourceRoleName("fooRole"), ResourceRoleName("barRole"), ResourceRoleName("bazRole"))),
      eqTo(Set(ResourceAction("fooAction"), ResourceAction("barAction"))),
      eqTo(true),
      any[SamRequestContext]
    )
  }
}
