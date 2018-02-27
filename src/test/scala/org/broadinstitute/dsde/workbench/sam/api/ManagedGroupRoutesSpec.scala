package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.{TestSupport, model}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService
import org.scalatest.{BeforeAndAfter, FlatSpec, GivenWhenThen, Matchers}

/**
  * Created by dvoet on 6/7/17.
  */
class ManagedGroupRoutesSpec extends FlatSpec with Matchers with ScalatestRouteTest with TestSupport with BeforeAndAfter {

  private val ownerRoleName = ResourceRoleName("admin")
  private val ownerPolicyName = AccessPolicyName(ownerRoleName.value)
  private val memberPolicyName = AccessPolicyName(ManagedGroupService.MemberRoleName.value)
  private val accessPolicyNames = Set(ownerPolicyName, memberPolicyName)
  private val policyActions: Set[ResourceAction] = accessPolicyNames.flatMap(policyName => Set(SamResourceActions.sharePolicy(policyName), SamResourceActions.readPolicy(policyName)))
  private val resourceActions = Set(ResourceAction("delete")) union policyActions
  private val resourceActionPatterns = resourceActions.map(action => ResourceActionPattern(action.value))
  private val defaultOwnerRole = ResourceRole(ownerRoleName, resourceActions)
  private val defaultRoles = Set(defaultOwnerRole, ResourceRole(ManagedGroupService.MemberRoleName, Set.empty))
  private val managedGroupResourceType = ResourceType(ManagedGroupService.ManagedGroupTypeName, resourceActionPatterns, defaultRoles, ownerRoleName)
  private val resourceTypes = Map(managedGroupResourceType.name -> managedGroupResourceType)
  private val groupId = "foo"

  def assertGroupDoesNotExist(samRoutes: TestSamRoutes, groupId: String = groupId) {
    Get(s"/api/group/$groupId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  def assertCreateGroup(samRoutes: TestSamRoutes, groupId: String = groupId): Unit = {
    Post("/api/group/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
      responseAs[String].isEmpty shouldEqual true
    }
  }

  def assertGetGroup(samRoutes: TestSamRoutes, groupId: String = groupId) = {
    Get(s"/api/group/$groupId") ~> samRoutes.route ~> check {
      val expectedResource = Resource(ManagedGroupService.ManagedGroupTypeName, ResourceId(groupId))
      status shouldEqual StatusCodes.OK
      responseAs[String].isEmpty shouldEqual false
      responseAs[String] should include(ResourceAndPolicyName(expectedResource, memberPolicyName).toString())
      responseAs[String] should include(ResourceAndPolicyName(expectedResource, ownerPolicyName).toString())
    }
  }

  def assertDeleteGroup(samRoutes: TestSamRoutes, groupId: String = groupId) = {
    Delete(s"/api/group/$groupId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
      responseAs[String].isEmpty shouldEqual true
    }
  }

  //  "POST /api/group/{groupName}" should "create a new managed group, the owner group, and the members group, and the All Group and all policies for those groups" in {
  "POST /api/group/{groupName}" should "create a new managed group with a 204 response code" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertGroupDoesNotExist(samRoutes)
    assertCreateGroup(samRoutes)
  }

  // TODO: Confirm with Ursa and/or Doug about whether we want a "full" Group object returned or just the list of group members
  "GET /api/group/{groupName}" should "return a flattened list of users who are in this group" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertGroupDoesNotExist(samRoutes)
    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)
  }

  // TODO: Ask Doug if these routes are needed?
  "GET /api/group/{groupName}/owners" should "return the flattened list of users who are owners of this group" is pending
  "GET /api/group/{groupName}/members" should "return the flattened list of users who are non-owner members of this group" is pending

  "DELETE /api/group/{groupName}" should "delete the group, member groups, and all associated policies when the authenticated user is an owner of the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)
    assertDeleteGroup(samRoutes)
    assertGroupDoesNotExist(samRoutes)
  }

  // Note:  Ignored for now as I could not get it to pass.
  // 1. Perhaps the way I was swapping users was incorrect
  // 2. Maybe there is something deeper in the guts of "delete" that needs to explicitly check the policy to delete
  it should "fail if the authenticated user user is not an owner of the group" in {
    val defaultRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(defaultRoutes)
    assertGetGroup(defaultRoutes)

    val theDude = UserInfo(OAuth2BearerToken("tokenDude"), WorkbenchUserId("ElDudarino"), WorkbenchEmail("ElDudarino@example.com"), 0)
    val dudesRoutes = new TestSamRoutes(defaultRoutes.resourceService, defaultRoutes.userService, defaultRoutes.statusService, defaultRoutes.managedGroupService, theDude, defaultRoutes.mockDirectoryDao)

    // TODO: Should a random user be allowed to GET a group?  Or should we be reporting Unauthorized on this call?
    assertGetGroup(dudesRoutes)
    Delete(s"/api/group/$groupId") ~> dudesRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
    assertGetGroup(dudesRoutes)
    assertGetGroup(defaultRoutes)
  }
}