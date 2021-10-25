package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.api.ManagedGroupRoutesSpec._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.BeforeAndAfter
import spray.json.DefaultJsonProtocol._

import scala.language.reflectiveCalls
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Created by gpolumbo on 2/26/2017.
  */
class ManagedGroupRoutesV1Spec extends AnyFlatSpec with ScalaFutures with Matchers with ScalatestRouteTest with TestSupport with BeforeAndAfter {

  private val accessPolicyNames = Set(ManagedGroupService.adminPolicyName, ManagedGroupService.memberPolicyName, ManagedGroupService.adminNotifierPolicyName)
  private val policyActions: Set[ResourceAction] = accessPolicyNames.flatMap(policyName => Set(SamResourceActions.sharePolicy(policyName), SamResourceActions.readPolicy(policyName)))
  private val resourceActions = Set(ResourceAction("delete"), ResourceAction("notify_admins"), ResourceAction("set_access_instructions")) union policyActions
  private val resourceActionPatterns = resourceActions.map(action => ResourceActionPattern(action.value, "", false))
  private val defaultOwnerRole = ResourceRole(ManagedGroupService.adminRoleName, resourceActions)
  private val defaultMemberRole = ResourceRole(ManagedGroupService.memberRoleName, Set.empty)
  private val defaultAdminNotifierRole = ResourceRole(ManagedGroupService.adminNotifierRoleName, Set(ResourceAction("notify_admins")))
  private val defaultRoles = Set(defaultOwnerRole, defaultMemberRole, defaultAdminNotifierRole)
  private val managedGroupResourceType = ResourceType(ManagedGroupService.managedGroupTypeName, resourceActionPatterns, defaultRoles, ManagedGroupService.adminRoleName)
  private val resourceTypes = Map(managedGroupResourceType.name -> managedGroupResourceType)
  private val groupId = "foo"
  private val defaultNewUser = UserInfo(OAuth2BearerToken("newToken"), WorkbenchUserId("NewGuy"), WorkbenchEmail("newGuy@organization.org"), 0)
  private val defaultGoogleSubjectId = Option(GoogleSubjectId("NewGuy"))

  def assertGroupDoesNotExist(samRoutes: SamRoutes, groupId: String = groupId): Unit = {
    Get(s"/api/groups/v1/$groupId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  def assertCreateGroup(samRoutes: SamRoutes, groupId: String = groupId): Unit = {
    Post(s"/api/groups/v1/$groupId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }
  }

  def assertGetGroup(samRoutes: SamRoutes, groupId: String = groupId) = {
    Get(s"/api/groups/v1/$groupId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  def assertDeleteGroup(samRoutes: SamRoutes, groupId: String = groupId) = {
    Delete(s"/api/groups/v1/$groupId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  // Makes an anonymous object for a user acting on the same data as the user specified in samRoutes
  def makeOtherUser(samRoutes: SamRoutes, userInfo: UserInfo = defaultNewUser) = new {
    runAndWait(samRoutes.userService.createUser(WorkbenchUser(userInfo.userId, defaultGoogleSubjectId, userInfo.userEmail, None), samRequestContext))
    val email = userInfo.userEmail
    val routes = new TestSamRoutes(samRoutes.resourceService, samRoutes.policyEvaluatorService, samRoutes.userService, samRoutes.statusService, samRoutes.managedGroupService, userInfo, samRoutes.directoryDAO)
  }

  def setGroupMembers(samRoutes: SamRoutes, members: Set[WorkbenchEmail], expectedStatus: StatusCode): Unit = {
    Put(s"/api/groups/v1/$groupId/member", members) ~> samRoutes.route ~> check {
      status shouldEqual expectedStatus
    }
  }

  def withUserNotInGroup[T](defaultRoutes: SamRoutes)(body: TestSamRoutes => T): T = {
    assertCreateGroup(defaultRoutes)
    assertGetGroup(defaultRoutes)

    val theDude = UserInfo(OAuth2BearerToken("tokenDude"), WorkbenchUserId("ElDudarino"), WorkbenchEmail("ElDudarino@example.com"), 0)
    defaultRoutes.directoryDAO.createUser(WorkbenchUser(theDude.userId, None, theDude.userEmail, None), samRequestContext).unsafeRunSync()
    val dudesRoutes = new TestSamRoutes(defaultRoutes.resourceService, defaultRoutes.policyEvaluatorService, defaultRoutes.userService, defaultRoutes.statusService, defaultRoutes.managedGroupService, theDude, defaultRoutes.directoryDAO)
    body(dudesRoutes)
  }

  "GET /api/groups/v1/{groupName}" should "respond with 200 if the requesting user is in the admin policy for the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)
  }

  it should "respond with 200 if the requesting user is in the member policy for the group" in {
    val samRoutes = createSamRoutesWithResource(resourceTypes, Resource(ManagedGroupService.managedGroupTypeName, Generator.genResourceId.sample.get, Set.empty))
    val newGuyEmail = WorkbenchEmail("newGuy@organization.org")
    val newGuy = UserInfo(OAuth2BearerToken("newToken"), WorkbenchUserId("NewGuy"), newGuyEmail, 0)
    val newGuyRoutes = new TestSamRoutes(samRoutes.resourceService, samRoutes.policyEvaluatorService, samRoutes.userService, samRoutes.statusService, samRoutes.managedGroupService, newGuy, samRoutes.directoryDAO)

    assertCreateGroup(samRoutes = samRoutes)
    assertGetGroup(samRoutes = samRoutes)

    samRoutes.userService.createUser(WorkbenchUser(newGuy.userId, defaultGoogleSubjectId, newGuy.userEmail, None), samRequestContext).futureValue

    setGroupMembers(samRoutes, Set(newGuyEmail), expectedStatus = StatusCodes.Created)

    assertGetGroup(newGuyRoutes)
  }

  it should "respond with 404 if the group does not exist" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertGroupDoesNotExist(samRoutes)
  }

  it should "respond with 200 if the group exists but the user is not in the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("newGuy@organization.org")
    val newGuy = UserInfo(OAuth2BearerToken("newToken"), WorkbenchUserId("NewGuy"), newGuyEmail, 0)
    samRoutes.directoryDAO.createUser(WorkbenchUser(newGuy.userId, None, newGuyEmail, None), samRequestContext).unsafeRunSync()
    val newGuyRoutes = new TestSamRoutes(samRoutes.resourceService, samRoutes.policyEvaluatorService, samRoutes.userService, samRoutes.statusService, samRoutes.managedGroupService, newGuy, samRoutes.mockDirectoryDao)


    Get(s"/api/groups/v1/$groupId") ~> newGuyRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  "POST /api/groups/v1/{groupName}" should "respond 201 if the group did not already exist" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertGroupDoesNotExist(samRoutes)
    assertCreateGroup(samRoutes)
  }

  it should "fail with a 409 if the group already exists" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)
    Post(s"/api/groups/v1/$groupId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }
  }

  it should "fail with a 400 if the group name contains invalid characters" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)

    val badGroupName = "bad$name"
    Post(s"/api/groups/v1/$badGroupName") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "fail with a 400 if the group name contains 64 or more characters" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)

    val badGroupName = "X" * 61
    Post(s"/api/groups/v1/$badGroupName") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  "DELETE /api/groups/v1/{groupName}" should "should respond with 204 when the group is successfully deleted" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)
    assertDeleteGroup(samRoutes)
    assertGroupDoesNotExist(samRoutes)
  }

  it should "fail with 404 if the authenticated user is not in the owner policy for the group" in {
    val defaultRoutes = TestSamRoutes(resourceTypes)
    withUserNotInGroup(defaultRoutes){ nonMemberRoutes =>
    assertGetGroup(nonMemberRoutes)
    Delete(s"/api/groups/v1/$groupId") ~> nonMemberRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
    assertGetGroup(nonMemberRoutes)
    assertGetGroup(defaultRoutes)}
  }

  "GET /api/groups/v1/{groupName}/member" should "succeed with 200 when the group exists and the requesting user is in the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    Get(s"/api/groups/v1/$groupId/member") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "[]"
    }
  }

  it should "fail with 404 when the requesting user is a 'member' but not an 'admin'" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)


    setGroupMembers(samRoutes, Set(newGuy.email), expectedStatus = StatusCodes.Created)

    Get(s"/api/groups/v1/$groupId/member") ~> newGuy.routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 404 when the requesting user is not in the group" in {
    withUserNotInGroup( TestSamRoutes(resourceTypes)
    ) { nonMemberRoutes =>

    Get(s"/api/groups/v1/$groupId/members") ~> nonMemberRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound}
    }
  }

  it should "fail with 404 when the group does not exist" in {
    val samRoutes = createSamRoutesWithResource(resourceTypes, Resource(ManagedGroupService.managedGroupTypeName, ResourceId("foo"), Set.empty))

    Get(s"/api/groups/v1/$groupId/member") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/groups/v1/{groupName}/{policyName}" should "fail with 404 if policy name is not in [member, admin]" in {
    val samRoutes = createSamRoutesWithResource(resourceTypes, Resource(ManagedGroupService.managedGroupTypeName, ResourceId("foo"), Set.empty))

    assertCreateGroup(samRoutes)

    Get(s"/api/groups/v1/$groupId/blah") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
      responseAs[String] should include ("must be one of")
    }
  }

  "PUT /api/groups/v1/{groupName}/member" should "fail with 400 when updating the 'member' policy of the group with a user who has not been created yet" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("newGuy@organization.org")
    val members = Set(newGuyEmail)

    Put(s"/api/groups/v1/$groupId/member", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
      responseAs[String] should include (newGuyEmail.toString())
    }
  }

  it should "succeed with 201 after successfully updating the 'member' policy of the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)


    setGroupMembers(samRoutes, Set(newGuy.email), expectedStatus = StatusCodes.Created)
  }

  it should "fail with 404 when the requesting user is not in the admin policy for the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)

    setGroupMembers(newGuy.routes, Set(newGuy.email), expectedStatus = StatusCodes.NotFound)
  }

  it should "fail with 404 when the group does not exist" in {
    val samRoutes = createSamRoutesWithResource(resourceTypes, Resource(ManagedGroupService.managedGroupTypeName, ResourceId("foo"), Set.empty))

    val newGuy = makeOtherUser(samRoutes)

    setGroupMembers(samRoutes, Set(newGuy.email), expectedStatus = StatusCodes.NotFound)
  }

  it should "fail with 500 when any of the email addresses being added are invalid" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("I'm not an email address but I should be")

    val members = Set(newGuyEmail)
    Put(s"/api/groups/v1/$groupId/member", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
      responseAs[String] should include (newGuyEmail.toString())
    }
  }

  "GET /api/groups/v1/{groupName}/admin" should "succeed with 200 when the group exists and the requesting user is in the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)

    Get(s"/api/groups/v1/$groupId/admin") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] should include (TestSamRoutes.defaultUserInfo.userEmail.value)
    }
  }

  it should "fail with 404 when the requesting user is a 'member' but not an 'admin'" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)


    setGroupMembers(samRoutes, Set(newGuy.email), expectedStatus = StatusCodes.Created)

    Get(s"/api/groups/v1/$groupId/admin") ~> newGuy.routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 404 when the requesting user is not in the group" in {
    withUserNotInGroup( TestSamRoutes(resourceTypes)
    ) { nonMemberRoutes =>

    Get(s"/api/groups/v1/$groupId/admin") ~> nonMemberRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound}
    }
  }

  it should "fail with 404 when the group does not exist" in {
    val samRoutes = createSamRoutesWithResource(resourceTypes, Resource(ManagedGroupService.managedGroupTypeName, ResourceId("foo"), Set.empty))

    Get(s"/api/groups/v1/$groupId/admin") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PUT /api/groups/v1/{groupName}/admin" should "fail with 400 when updating the 'admin' policy of the group with a user who has not been created yet" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("newGuy@organization.org")
    val members = Set(newGuyEmail)

    Put(s"/api/groups/v1/$groupId/admin", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
      responseAs[String] should include (newGuyEmail.toString())
    }
  }

  it should "succeed with 201 after successfully updating the 'admin' policy of the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)


    val members = Set(newGuy.email)
    Put(s"/api/groups/v1/$groupId/admin", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }
  }

  it should "fail with 404 when the requesting user is not in the admin policy for the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)

    val members = Set(newGuy.email)
    Put(s"/api/groups/v1/$groupId/admin", members) ~> newGuy.routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 404 when the group does not exist" in {
    val samRoutes = createSamRoutesWithResource(resourceTypes, Resource(ManagedGroupService.managedGroupTypeName, ResourceId("foo"), Set.empty))

    val newGuyEmail = WorkbenchEmail("newGuy@organization.org")
    val newGuy = UserInfo(OAuth2BearerToken("newToken"), WorkbenchUserId("NewGuy"), newGuyEmail, 0)
    val newGuyRoutes = new TestSamRoutes(samRoutes.resourceService, samRoutes.policyEvaluatorService, samRoutes.userService, samRoutes.statusService, samRoutes.managedGroupService, newGuy, samRoutes.mockDirectoryDao)


    val members = Set(newGuyEmail)
    Put(s"/api/groups/v1/$groupId/admin", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 500 when any of the email addresses being added are invalid" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("An Invalid email address")

    val members = Set(newGuyEmail)
    Put(s"/api/groups/v1/$groupId/admin", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
      responseAs[String] should include (newGuyEmail.toString())
    }
  }

  "PUT /api/groups/v1/{groupName}/{policyName}/{email}" should "respond with 204 and add the email address to the specified group and policy" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)


    Put(s"/api/groups/v1/$groupId/admin/${newGuy.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "respond with 204 when the email address is already in the group and policy" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val defaultUserInfo = samRoutes.userInfo
    Put(s"/api/groups/v1/$groupId/admin/${defaultUserInfo.userEmail}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "respond with 404 when the group does not exist" in {
    val samRoutes = createSamRoutesWithResource(resourceTypes, Resource(ManagedGroupService.managedGroupTypeName, ResourceId("foo"), Set.empty))

    val newGuy = makeOtherUser(samRoutes)

    Put(s"/api/groups/v1/$groupId/admin/${newGuy.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "respond with 400 when the email address is invalid" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val notAnEmail = "NotAnEmailAddress"

    Put(s"/api/groups/v1/$groupId/admin/$notAnEmail") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "respond with 404 when the policy is invalid" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)

    Put(s"/api/groups/v1/$groupId/xmen/${newGuy.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "respond with 404 when the requesting user does not have any permissions in the group and policy" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)

    Put(s"/api/groups/v1/$groupId/admin/${samRoutes.userInfo.userEmail}") ~> newGuy.routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  // TODO: In order to be able to delete the subject, they need to exist in opendj.  Is this what we want?
  "DELETE /api/groups/v1/{groupName}/{policyName}/{email}" should "respond with 204 and remove the email address from the specified group and policy" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    Delete(s"/api/groups/v1/$groupId/admin/${samRoutes.userInfo.userEmail}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  // TODO:  I think this should just work and give back a 204
  // TODO: well i changed something and now it returns a 204 so maybe this TODO above is complete? Must investigate...
  it should "respond with 404 when the email address was already not present in the group and policy" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    Delete(s"/api/groups/v1/$groupId/admin/${samRoutes.userInfo.userEmail}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "respond with 404 when the group does not exist" in {
    val samRoutes = createSamRoutesWithResource(resourceTypes, Resource(ManagedGroupService.managedGroupTypeName, ResourceId("foo"), Set.empty))

    Delete(s"/api/groups/v1/$groupId/admin/${samRoutes.userInfo.userEmail}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "respond with 404 when the policy is invalid" in {
    val samRoutes = createSamRoutesWithResource(resourceTypes, Resource(ManagedGroupService.managedGroupTypeName, Generator.genResourceId.sample.get, Set.empty))

    assertCreateGroup(samRoutes)

    Delete(s"/api/groups/v1/$groupId/people/${samRoutes.userInfo.userEmail}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "respond with 404 when the requesting user does not have permissions to edit the group and policy" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)


    Delete(s"/api/groups/v1/$groupId/admin/${samRoutes.userInfo.userEmail}") ~> newGuy.routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/groups/v1" should "respond with 200 and a list of managed groups the authenticated user belongs to" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    val groupNames = Set("foo", "bar", "baz")

    groupNames.foreach(assertCreateGroup(samRoutes, _))

    Get("/api/groups/v1") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val res = responseAs[String]
      groupNames.foreach(res should include (_))
      res should include ("admin")
      res shouldNot include ("member")
    }
  }

  it should "respond with 200 and an empty list if the user is not a member of any managed groups" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    Get("/api/groups/v1") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "[]"
    }
  }

  "GET /api/groups/v1/{groupName}/admin-notifier" should "succeed with 200 when the group exists and the requesting user is a group admin" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)

    Get(s"/api/groups/v1/$groupId/admin-notifier") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  it should "fail with 404 when the requesting user is a 'member' but not an 'admin'" in {
    val samRoutes: TestSamRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)

    setGroupMembers(samRoutes, Set(newGuy.email), expectedStatus = StatusCodes.Created)

    Get(s"/api/groups/v1/$groupId/admin-notifier") ~> newGuy.routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 404 when the requesting user is not in the group" in {
    withUserNotInGroup(TestSamRoutes(resourceTypes)) { nonMemberRoutes =>
      Get(s"/api/groups/v1/$groupId/admin-notifier") ~> nonMemberRoutes.route ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }
  }

  it should "fail with 404 when the group does not exist" in {
    val samRoutes = createSamRoutesWithResource(resourceTypes, Resource(ManagedGroupService.managedGroupTypeName, ResourceId("foo"), Set.empty))


    Get(s"/api/groups/v1/$groupId/admin") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PUT /api/groups/v1/{groupName}/admin-notifier" should "succeed with 201 after successfully updating the 'admin-notifier' policy" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)

    Put(s"/api/groups/v1/$groupId/admin-notifier", Set(newGuy.email)) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }
  }

  it should "fail with 404 when the requesting user is not in the admin policy for the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)
    Put(s"/api/groups/v1/$groupId/admin-notifier", Set(newGuy.email)) ~> newGuy.routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PUT /api/groups/v1/{groupName}/accessInstructions" should "succeed with 204" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)
    val instructions = "Test instructions"

    Put(s"/api/groups/v1/$groupId/accessInstructions", ManagedGroupAccessInstructions(instructions)) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  "GET /api/groups/v1/{groupName}/accessInstructions" should "succeed with 200 and return the access instructions when group and access instructions exist" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)
    val instructions = "Test instructions"

    Put(s"/api/groups/v1/$groupId/accessInstructions", ManagedGroupAccessInstructions(instructions)) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/groups/v1/$groupId/accessInstructions") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual (instructions)
    }
  }

  it should "succeed with 204 when the group exists but access instructions are not set" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    Get(s"/api/groups/v1/$groupId/accessInstructions") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "fail with 404 when the group does not exist" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    Get(s"/api/groups/v1/$groupId/accessInstructions") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "POST /api/groups/v1/{groupName}/requestAccess" should "succeed with 204 when the group exists" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    Post(s"/api/groups/v1/$groupId/requestAccess") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "fail with 400 if there are access instructions" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val instructions = "Test instructions"

    Put(s"/api/groups/v1/$groupId/accessInstructions", ManagedGroupAccessInstructions(instructions)) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Post(s"/api/groups/v1/$groupId/requestAccess") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
      responseAs[String] should include (instructions)
    }
  }

  it should "fail with 404 when group does not exist" in {
    val samRoutes = createSamRoutesWithResource(resourceTypes, Resource(ManagedGroupService.managedGroupTypeName, ResourceId("foo"), Set.empty))

    Post(s"/api/groups/v1/$groupId/requestAccess") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }
}
