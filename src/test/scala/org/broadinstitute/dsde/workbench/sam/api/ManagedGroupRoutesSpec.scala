package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.model.{UserInfo, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.{TestSupport, model}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService
import org.scalatest.{BeforeAndAfter, FlatSpec, GivenWhenThen, Matchers}

/**
  * Created by gpolumbo on 2/26/2017.
  */
class ManagedGroupRoutesSpec extends FlatSpec with Matchers with ScalatestRouteTest with TestSupport with BeforeAndAfter {

  private val ownerRoleName = ResourceRoleName("admin")
  private val ownerPolicyName = AccessPolicyName(ownerRoleName.value)
  private val memberPolicyName = AccessPolicyName(ManagedGroupService.memberRoleName.value)
  private val accessPolicyNames = Set(ownerPolicyName, memberPolicyName)
  private val policyActions: Set[ResourceAction] = accessPolicyNames.flatMap(policyName => Set(SamResourceActions.sharePolicy(policyName), SamResourceActions.readPolicy(policyName)))
  private val resourceActions = Set(ResourceAction("delete")) union policyActions
  private val resourceActionPatterns = resourceActions.map(action => ResourceActionPattern(action.value))
  private val defaultOwnerRole = ResourceRole(ownerRoleName, resourceActions)
  private val defaultRoles = Set(defaultOwnerRole, ResourceRole(ManagedGroupService.memberRoleName, Set.empty))
  private val managedGroupResourceType = ResourceType(ManagedGroupService.managedGroupTypeName, resourceActionPatterns, defaultRoles, ownerRoleName)
  private val resourceTypes = Map(managedGroupResourceType.name -> managedGroupResourceType)
  private val groupId = "foo"

  def assertGroupDoesNotExist(samRoutes: TestSamRoutes, groupId: String = groupId) {
    Get(s"/api/group/$groupId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  def assertCreateGroup(samRoutes: TestSamRoutes, groupId: String = groupId): Unit = {
    Post(s"/api/group/$groupId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }
  }

  def assertGetGroup(samRoutes: TestSamRoutes, groupId: String = groupId) = {
    Get(s"/api/group/$groupId") ~> samRoutes.route ~> check {
      val expectedResource = Resource(ManagedGroupService.managedGroupTypeName, ResourceId(groupId))
      status shouldEqual StatusCodes.OK
    }
  }

  def assertDeleteGroup(samRoutes: TestSamRoutes, groupId: String = groupId) = {
    Delete(s"/api/group/$groupId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  "POST /api/group/{groupName}" should "respond 201 if the group did not already exist" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertGroupDoesNotExist(samRoutes)
    assertCreateGroup(samRoutes)
  }

  it should "fail with a 409 if the group already exists" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)
    Post(s"/api/group/$groupId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }

  it should "fail with a 400 if the group name contains invalid characters" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)

    val badGroupName = "bad$name"
    Post(s"/api/group/$badGroupName") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "fail with a 400 if the group name contains 64 or more characters" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)

    val badGroupName = "X" * 64
    Post(s"/api/group/$badGroupName") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  "DELETE /api/group/{groupName}" should "should respond with 204 when the group is successfully deleted" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)
    assertDeleteGroup(samRoutes)
    assertGroupDoesNotExist(samRoutes)
  }

  it should "fail with 404 if the authenticated user is not in the owner policy for the group" in {
    val defaultRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(defaultRoutes)
    assertGetGroup(defaultRoutes)

    val theDude = UserInfo(OAuth2BearerToken("tokenDude"), WorkbenchUserId("ElDudarino"), WorkbenchEmail("ElDudarino@example.com"), 0)
    val dudesRoutes = new TestSamRoutes(defaultRoutes.resourceService, defaultRoutes.userService, defaultRoutes.statusService, defaultRoutes.managedGroupService, theDude, defaultRoutes.mockDirectoryDao)

    assertGetGroup(dudesRoutes)
    Delete(s"/api/group/$groupId") ~> dudesRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
    assertGetGroup(dudesRoutes)
    assertGetGroup(defaultRoutes)
  }

  "GET /api/group/{groupName}/members" should "succeed with 200 when the group exists and the requesting user is in the group" is pending
  it should "fail with 403 when the requesting user is not in the group" is pending
  it should "fail with 404 when the group does not exist" is pending

  "PUT /api/group/{groupName}/members" should "succeed with 201 after successfully updating the 'member' policy of the group" is pending
  it should "fail with 403 when the requesting user is not in the admin policy for the group" is pending
  it should "fail with 404 when the group does not exist" is pending
  it should "fail with 500 when any of the email addresses being added are invalid"

  "GET /api/group/{groupName}/admins" should "succeed with 200 when the group exists and the requesting user is in the group" is pending
  it should "fail with 403 when the requesting user is not in the group" is pending
  it should "fail with 404 when the group does not exist" is pending

  "PUT /api/group/{groupName}/admins" should "succeed with 201 after successfully updating the 'admin' policy of the group" is pending
  it should "fail with 403 when the requesting user is not in the admin policy for the group" is pending
  it should "fail with 404 when the group does not exist" is pending
  it should "fail with 500 when any of the email addresses being added are invalid"

}