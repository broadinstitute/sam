package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}
import spray.json.DefaultJsonProtocol._

/**
  * Created by gpolumbo on 2/26/2017.
  */
class ManagedGroupRoutesSpec extends FlatSpec with Matchers with ScalatestRouteTest with TestSupport with BeforeAndAfter {

  private val accessPolicyNames = Set(ManagedGroupService.adminPolicyName, ManagedGroupService.memberPolicyName)
  private val policyActions: Set[ResourceAction] = accessPolicyNames.flatMap(policyName => Set(SamResourceActions.sharePolicy(policyName), SamResourceActions.readPolicy(policyName)))
  private val resourceActions = Set(ResourceAction("delete")) union policyActions
  private val resourceActionPatterns = resourceActions.map(action => ResourceActionPattern(action.value))
  private val defaultOwnerRole = ResourceRole(ManagedGroupService.adminRoleName, resourceActions)
  private val defaultMemberRole = ResourceRole(ManagedGroupService.memberRoleName, Set.empty)
  private val defaultRoles = Set(defaultOwnerRole, defaultMemberRole)
  private val managedGroupResourceType = ResourceType(ManagedGroupService.managedGroupTypeName, resourceActionPatterns, defaultRoles, ManagedGroupService.adminRoleName)
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

  def assertCreateUser(samRoutes: TestSamRoutes) = {
    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
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

  "GET /api/group/{groupName}/members" should "succeed with 200 when the group exists and the requesting user is in the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    Get(s"/api/group/$groupId/members") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "[]"
    }
  }

  it should "fail with 404 when the requesting user is a 'member' but not an 'admin'" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("newGuy@organization.org")
    val newGuy = UserInfo(OAuth2BearerToken("newToken"), WorkbenchUserId("NewGuy"), newGuyEmail, 0)
    val newGuyRoutes = new TestSamRoutes(samRoutes.resourceService, samRoutes.userService, samRoutes.statusService, samRoutes.managedGroupService, newGuy, samRoutes.mockDirectoryDao)
    assertCreateUser(newGuyRoutes)

    val members = Set(newGuyEmail)
    Put(s"/api/group/$groupId/members", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    Get(s"/api/group/$groupId/members") ~> newGuyRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 404 when the requesting user is not in the group" in {
    val defaultRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(defaultRoutes)
    assertGetGroup(defaultRoutes)

    val theDude = UserInfo(OAuth2BearerToken("tokenDude"), WorkbenchUserId("ElDudarino"), WorkbenchEmail("ElDudarino@example.com"), 0)
    val dudesRoutes = new TestSamRoutes(defaultRoutes.resourceService, defaultRoutes.userService, defaultRoutes.statusService, defaultRoutes.managedGroupService, theDude, defaultRoutes.mockDirectoryDao)

    Get(s"/api/group/$groupId/members") ~> dudesRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 404 when the group does not exist" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    Get(s"/api/group/$groupId/members") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/group/{groupName}/{policyName}" should "fail with 404 if policy name is not in [members, admins]" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    Get(s"/api/group/$groupId/blah") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
      responseAs[String] should include ("must be one of")
    }
  }

  "PUT /api/group/{groupName}/members" should "fail with 400 when updating the 'member' policy of the group with a user who has not been created yet" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("newGuy@organization.org")
    val members = Set(newGuyEmail)

    Put(s"/api/group/$groupId/members", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
      responseAs[String] should include (newGuyEmail.toString())
    }
  }

  it should "succeed with 201 after successfully updating the 'member' policy of the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("newGuy@organization.org")
    val newGuy = UserInfo(OAuth2BearerToken("newToken"), WorkbenchUserId("NewGuy"), newGuyEmail, 0)
    val newGuyRoutes = new TestSamRoutes(samRoutes.resourceService, samRoutes.userService, samRoutes.statusService, samRoutes.managedGroupService, newGuy, samRoutes.mockDirectoryDao)
    assertCreateUser(newGuyRoutes)

    val members = Set(newGuyEmail)
    Put(s"/api/group/$groupId/members", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }
  }

  it should "fail with 404 when the requesting user is not in the admin policy for the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("newGuy@organization.org")
    val newGuy = UserInfo(OAuth2BearerToken("newToken"), WorkbenchUserId("NewGuy"), newGuyEmail, 0)
    val newGuyRoutes = new TestSamRoutes(samRoutes.resourceService, samRoutes.userService, samRoutes.statusService, samRoutes.managedGroupService, newGuy, samRoutes.mockDirectoryDao)
    assertCreateUser(newGuyRoutes)

    val members = Set(newGuyEmail)
    Put(s"/api/group/$groupId/members", members) ~> newGuyRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 404 when the group does not exist" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    val newGuyEmail = WorkbenchEmail("newGuy@organization.org")
    val newGuy = UserInfo(OAuth2BearerToken("newToken"), WorkbenchUserId("NewGuy"), newGuyEmail, 0)
    val newGuyRoutes = new TestSamRoutes(samRoutes.resourceService, samRoutes.userService, samRoutes.statusService, samRoutes.managedGroupService, newGuy, samRoutes.mockDirectoryDao)
    assertCreateUser(newGuyRoutes)

    val members = Set(newGuyEmail)
    Put(s"/api/group/$groupId/members", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 500 when any of the email addresses being added are invalid" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("I'm not an email address but I should be")

    val members = Set(newGuyEmail)
    Put(s"/api/group/$groupId/members", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
      responseAs[String] should include (newGuyEmail.toString())
    }
  }

  "GET /api/group/{groupName}/admins" should "succeed with 200 when the group exists and the requesting user is in the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateUser(samRoutes)
    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)

    Get(s"/api/group/$groupId/admins") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] should include (TestSamRoutes.defaultUserInfo.userEmail.value)
    }
  }

  it should "fail with 404 when the requesting user is a 'member' but not an 'admin'" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("newGuy@organization.org")
    val newGuy = UserInfo(OAuth2BearerToken("newToken"), WorkbenchUserId("NewGuy"), newGuyEmail, 0)
    val newGuyRoutes = new TestSamRoutes(samRoutes.resourceService, samRoutes.userService, samRoutes.statusService, samRoutes.managedGroupService, newGuy, samRoutes.mockDirectoryDao)
    assertCreateUser(newGuyRoutes)

    val members = Set(newGuyEmail)
    Put(s"/api/group/$groupId/members", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    Get(s"/api/group/$groupId/admins") ~> newGuyRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 404 when the requesting user is not in the group" in {
    val defaultRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(defaultRoutes)
    assertGetGroup(defaultRoutes)

    val theDude = UserInfo(OAuth2BearerToken("tokenDude"), WorkbenchUserId("ElDudarino"), WorkbenchEmail("ElDudarino@example.com"), 0)
    val dudesRoutes = new TestSamRoutes(defaultRoutes.resourceService, defaultRoutes.userService, defaultRoutes.statusService, defaultRoutes.managedGroupService, theDude, defaultRoutes.mockDirectoryDao)

    Get(s"/api/group/$groupId/admins") ~> dudesRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 404 when the group does not exist" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    Get(s"/api/group/$groupId/admins") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PUT /api/group/{groupName}/admins" should "fail with 400 when updating the 'admin' policy of the group with a user who has not been created yet" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("newGuy@organization.org")
    val members = Set(newGuyEmail)

    Put(s"/api/group/$groupId/admins", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
      responseAs[String] should include (newGuyEmail.toString())
    }
  }

  it should "succeed with 201 after successfully updating the 'admin' policy of the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("newGuy@organization.org")
    val newGuy = UserInfo(OAuth2BearerToken("newToken"), WorkbenchUserId("NewGuy"), newGuyEmail, 0)
    val newGuyRoutes = new TestSamRoutes(samRoutes.resourceService, samRoutes.userService, samRoutes.statusService, samRoutes.managedGroupService, newGuy, samRoutes.mockDirectoryDao)
    assertCreateUser(newGuyRoutes)

    val members = Set(newGuyEmail)
    Put(s"/api/group/$groupId/admins", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }
  }

  it should "fail with 404 when the requesting user is not in the admin policy for the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("newGuy@organization.org")
    val newGuy = UserInfo(OAuth2BearerToken("newToken"), WorkbenchUserId("NewGuy"), newGuyEmail, 0)
    val newGuyRoutes = new TestSamRoutes(samRoutes.resourceService, samRoutes.userService, samRoutes.statusService, samRoutes.managedGroupService, newGuy, samRoutes.mockDirectoryDao)
    assertCreateUser(newGuyRoutes)

    val members = Set(newGuyEmail)
    Put(s"/api/group/$groupId/admins", members) ~> newGuyRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 404 when the group does not exist" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    val newGuyEmail = WorkbenchEmail("newGuy@organization.org")
    val newGuy = UserInfo(OAuth2BearerToken("newToken"), WorkbenchUserId("NewGuy"), newGuyEmail, 0)
    val newGuyRoutes = new TestSamRoutes(samRoutes.resourceService, samRoutes.userService, samRoutes.statusService, samRoutes.managedGroupService, newGuy, samRoutes.mockDirectoryDao)
    assertCreateUser(newGuyRoutes)

    val members = Set(newGuyEmail)
    Put(s"/api/group/$groupId/admins", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 500 when any of the email addresses being added are invalid" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("An Invalid email address")

    val members = Set(newGuyEmail)
    Put(s"/api/group/$groupId/admins", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
      responseAs[String] should include (newGuyEmail.toString())
    }
  }

}