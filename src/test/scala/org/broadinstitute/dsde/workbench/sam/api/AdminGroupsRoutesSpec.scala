package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.model.WorkbenchGroupName
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes.resourceTypeAdmin
import org.broadinstitute.dsde.workbench.sam.model.SamResourceActions.adminReadSummaryInformation
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api.ManagedGroupModelJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.api.{ManagedGroupSupportSummary, SamUser}
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{AppendedClues, Assertion}

class AdminGroupsRoutesSpec extends AnyFlatSpec with Matchers with TestSupport with ScalatestRouteTest with AppendedClues with MockitoSugar {
  private val adminUser = Generator.genFirecloudUser.sample.get
  private val nonAdminUser = Generator.genWorkbenchUserGoogle.sample.get

  private val managedGroupResourceType = ResourceType(
    ManagedGroupService.managedGroupTypeName,
    Set.empty,
    Set(
      ResourceRole(ResourceRoleName("admin"), Set.empty),
      ResourceRole(ResourceRoleName("member"), Set.empty),
      ResourceRole(ResourceRoleName("admin-notifier"), Set.empty)
    ),
    ResourceRoleName("admin")
  )

  private val adminPolicyName = AccessPolicyName("admin")
  private val adminResourceId = FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(managedGroupResourceType.name.value))
  private val managedGroupId = ResourceId("testGroup")

  def withSamRoutes(requester: SamUser)(testCode: SamRoutes => Assertion): Assertion =
    runAndWait {
      val routes = TestSamRoutes(Map(managedGroupResourceType.name -> managedGroupResourceType), user = requester)
      for {
        _ <- createAdminPolicy(routes)
        _ <- routes.managedGroupService.createManagedGroup(managedGroupId, requester, samRequestContext = samRequestContext)
      } yield testCode(routes)
    }

  private def createAdminPolicy(routes: TestSamRoutes) =
    routes.resourceService.createPolicy(
      FullyQualifiedPolicyId(adminResourceId, adminPolicyName),
      Set(adminUser.id),
      Set(ResourceRoleName("test")),
      Set(adminReadSummaryInformation),
      Set(),
      samRequestContext
    )

  "GET /api/admin/v1/groups/{groupName}/groups" should "200 when user has `admin_read_summary_information`" in
    withSamRoutes(requester = adminUser) { routes =>
      Get(s"/api/admin/v1/groups/${managedGroupId.value}/supportSummary") ~> routes.route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[ManagedGroupSupportSummary].groupName shouldEqual WorkbenchGroupName(managedGroupId.value)
      }
    }

  it should "404 when a user is not an admin for that resource type" in
    withSamRoutes(requester = nonAdminUser) { routes =>
      Get(s"/api/admin/v1/groups/${managedGroupId.value}/supportSummary") ~> routes.route ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

  it should "404 when the group does not exist" in
    withSamRoutes(requester = adminUser) { routes =>
      Get(s"/api/admin/v1/groups/doesnotexist/supportSummary") ~> routes.route ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }
}
