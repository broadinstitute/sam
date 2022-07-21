package org.broadinstitute.dsde.workbench.sam
package api

import akka.actor.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.stream.Materializer
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport.samRequestContext
import org.broadinstitute.dsde.workbench.sam.api.ManagedGroupRoutesSpec._
import org.broadinstitute.dsde.workbench.sam.dataAccess.{MockAccessPolicyDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService
import org.broadinstitute.dsde.workbench.sam.service.UserService.genRandom
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json.DefaultJsonProtocol._

import scala.concurrent.ExecutionContext
import scala.language.reflectiveCalls

/**
  * Created by gpolumbo on 2/26/2017.
  */
class ManagedGroupRoutesSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with TestSupport with BeforeAndAfter {

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
  private val defaultNewUser = Generator.genWorkbenchUserGoogle.sample.get
  private def defaultGoogleSubjectId = Option(GoogleSubjectId(genRandom(System.currentTimeMillis())))

  def assertGroupDoesNotExist(samRoutes: TestSamRoutes, groupId: String = groupId): Unit = {
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
      status shouldEqual StatusCodes.OK
    }
  }

  def assertDeleteGroup(samRoutes: TestSamRoutes, groupId: String = groupId) = {
    Delete(s"/api/group/$groupId") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  // Makes an anonymous object for a user acting on the same data as the user specified in samRoutes
  def makeOtherUser(samRoutes: TestSamRoutes, samUser: SamUser = defaultNewUser) = new {
    runAndWait(samRoutes.userService.createUser(samUser, samRequestContext))
    val email = samUser.email
    val routes = new TestSamRoutes(samRoutes.resourceService, samRoutes.policyEvaluatorService, samRoutes.userService, samRoutes.statusService, samRoutes.managedGroupService, samUser, samRoutes.mockDirectoryDao, samRoutes.mockRegistrationDao, tosService = samRoutes.tosService)
  }

  def setGroupMembers(samRoutes: TestSamRoutes, members: Set[WorkbenchEmail], expectedStatus: StatusCode): Unit = {
    Put(s"/api/group/$groupId/member", members) ~> samRoutes.route ~> check {
      status shouldEqual expectedStatus
    }
  }

  def withUserNotInGroup[T](defaultRoutes: TestSamRoutes)(body: TestSamRoutes => T): T = {
    assertCreateGroup(defaultRoutes)
    assertGetGroup(defaultRoutes)

    val theDude = Generator.genWorkbenchUserGoogle.sample.get.copy(enabled = true)
    defaultRoutes.directoryDAO.createUser(theDude, samRequestContext).unsafeRunSync()
    val dudesRoutes = new TestSamRoutes(defaultRoutes.resourceService, defaultRoutes.policyEvaluatorService, defaultRoutes.userService, defaultRoutes.statusService, defaultRoutes.managedGroupService, theDude, defaultRoutes.mockDirectoryDao, defaultRoutes.mockRegistrationDao, tosService = defaultRoutes.tosService)

    body(dudesRoutes)
  }

  "GET /api/group/{groupName}" should "respond with 200 if the requesting user is in the admin policy for the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)
  }

  it should "respond with 200 if the requesting user is in the member policy for the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    val newGuy = Generator.genWorkbenchUserGoogle.sample.get
    val newGuyRoutes = new TestSamRoutes(samRoutes.resourceService, samRoutes.policyEvaluatorService, samRoutes.userService, samRoutes.statusService, samRoutes.managedGroupService, newGuy, samRoutes.mockDirectoryDao, samRoutes.mockRegistrationDao, tosService = samRoutes.tosService)

    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)

    runAndWait(samRoutes.userService.createUser(newGuy, samRequestContext))

    setGroupMembers(samRoutes, Set(newGuy.email), expectedStatus = StatusCodes.Created)

    assertGetGroup(newGuyRoutes)
  }

  it should "respond with 404 if the group does not exist" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertGroupDoesNotExist(samRoutes)
  }

  it should "respond with 200 if the group exists but the user is not in the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = Generator.genWorkbenchUserGoogle.sample.get.copy(enabled = true)
    samRoutes.directoryDAO.createUser(newGuy, samRequestContext).unsafeRunSync()
    val newGuyRoutes = new TestSamRoutes(samRoutes.resourceService, samRoutes.policyEvaluatorService, samRoutes.userService, samRoutes.statusService, samRoutes.managedGroupService, newGuy, samRoutes.mockDirectoryDao, samRoutes.mockRegistrationDao, tosService = samRoutes.tosService)


    Get(s"/api/group/$groupId") ~> newGuyRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
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

    val badGroupName = "X" * 61
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
    withUserNotInGroup(defaultRoutes){ nonMemberRoutes =>
    assertGetGroup(nonMemberRoutes)
    Delete(s"/api/group/$groupId") ~> nonMemberRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
    assertGetGroup(nonMemberRoutes)
    assertGetGroup(defaultRoutes)}
  }

  "GET /api/group/{groupName}/member" should "succeed with 200 when the group exists and the requesting user is in the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    Get(s"/api/group/$groupId/member") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "[]"
    }
  }

  it should "fail with 404 when the requesting user is a 'member' but not an 'admin'" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)


    setGroupMembers(samRoutes, Set(newGuy.email), expectedStatus = StatusCodes.Created)

    Get(s"/api/group/$groupId/member") ~> newGuy.routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 404 when the requesting user is not in the group" in {
    withUserNotInGroup( TestSamRoutes(resourceTypes)
    ) { nonMemberRoutes =>

    Get(s"/api/group/$groupId/members") ~> nonMemberRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound}
    }
  }

  it should "fail with 404 when the group does not exist" in {
    val samRoutes = createSamRoutesWithResource(resourceTypes, Resource(ManagedGroupService.managedGroupTypeName, ResourceId("foo"), Set.empty))

    Get(s"/api/group/$groupId/member") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/group/{groupName}/{policyName}" should "fail with 404 if policy name is not in [member, admin]" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    Get(s"/api/group/$groupId/blah") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
      responseAs[String] should include ("must be one of")
    }
  }

  "PUT /api/group/{groupName}/member" should "fail with 400 when updating the 'member' policy of the group with a user who has not been created yet" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("newGuy@organization.org")
    val members = Set(newGuyEmail)

    Put(s"/api/group/$groupId/member", members) ~> samRoutes.route ~> check {
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
    Put(s"/api/group/$groupId/member", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
      responseAs[String] should include (newGuyEmail.toString())
    }
  }

  "GET /api/group/{groupName}/admin" should "succeed with 200 when the group exists and the requesting user is in the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)

    Get(s"/api/group/$groupId/admin") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] should include (TestSamRoutes.defaultUserInfo.email.value)
    }
  }

  it should "fail with 404 when the requesting user is a 'member' but not an 'admin'" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)


    setGroupMembers(samRoutes, Set(newGuy.email), expectedStatus = StatusCodes.Created)

    Get(s"/api/group/$groupId/admin") ~> newGuy.routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 404 when the requesting user is not in the group" in {
    withUserNotInGroup( TestSamRoutes(resourceTypes)
    ) { nonMemberRoutes =>

    Get(s"/api/group/$groupId/admin") ~> nonMemberRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound}
    }
  }

  it should "fail with 404 when the group does not exist" in {
    val samRoutes = createSamRoutesWithResource(resourceTypes, Resource(ManagedGroupService.managedGroupTypeName, ResourceId("foo"), Set.empty))

    Get(s"/api/group/$groupId/admin") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PUT /api/group/{groupName}/admin" should "fail with 400 when updating the 'admin' policy of the group with a user who has not been created yet" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("newGuy@organization.org")
    val members = Set(newGuyEmail)

    Put(s"/api/group/$groupId/admin", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
      responseAs[String] should include (newGuyEmail.toString())
    }
  }

  it should "succeed with 201 after successfully updating the 'admin' policy of the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)


    val members = Set(newGuy.email)
    Put(s"/api/group/$groupId/admin", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }
  }

  it should "fail with 404 when the requesting user is not in the admin policy for the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)

    val members = Set(newGuy.email)
    Put(s"/api/group/$groupId/admin", members) ~> newGuy.routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 404 when the group does not exist" in {
    val samRoutes = createSamRoutesWithResource(resourceTypes, Resource(ManagedGroupService.managedGroupTypeName, ResourceId("foo"), Set.empty))

    val newGuy = Generator.genWorkbenchUserGoogle.sample.get

    val members = Set(newGuy.email)
    Put(s"/api/group/$groupId/admin", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 500 when any of the email addresses being added are invalid" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuyEmail = WorkbenchEmail("An Invalid email address")

    val members = Set(newGuyEmail)
    Put(s"/api/group/$groupId/admin", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
      responseAs[String] should include (newGuyEmail.toString())
    }
  }

  "PUT /api/group/{groupName}/{policyName}/{email}" should "respond with 204 and add the email address to the specified group and policy" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)


    Put(s"/api/group/$groupId/admin/${newGuy.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "respond with 204 when the email address is already in the group and policy" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val defaultUserInfo = samRoutes.user
    Put(s"/api/group/$groupId/admin/${defaultUserInfo.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "respond with 404 when the group does not exist" in {
    val samRoutes = createSamRoutesWithResource(resourceTypes, Resource(ManagedGroupService.managedGroupTypeName, ResourceId("foo"), Set.empty))

    val newGuy = makeOtherUser(samRoutes)

    Put(s"/api/group/$groupId/admin/${newGuy.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "respond with 400 when the email address is invalid" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val notAnEmail = "NotAnEmailAddress"

    Put(s"/api/group/$groupId/admin/$notAnEmail") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "respond with 404 when the policy is invalid" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)


    Put(s"/api/group/$groupId/xmen/${newGuy.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "respond with 404 when the requesting user does not have any permissions in the group and policy" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)


    Put(s"/api/group/$groupId/admin/${samRoutes.user.email}") ~> newGuy.routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  // TODO: In order to be able to delete the subject, they need to exist in opendj.  Is this what we want?
  "DELETE /api/group/{groupName}/{policyName}/{email}" should "respond with 204 and remove the email address from the specified group and policy" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    Delete(s"/api/group/$groupId/admin/${samRoutes.user.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "respond with a 404 when the user does not have access to modify the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    withUserNotInGroup(samRoutes){ nonMemberRoutes =>
      Delete(s"/api/group/$groupId/admin/${samRoutes.user.email}") ~> nonMemberRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
      }
    }
  }

  // TODO:  I think this should just work and give back a 204
  // TODO: well i changed something and now it returns a 204 so maybe this TODO above is complete? Must investigate...
  it should "respond with 404 when the email address was already not present in the group and policy" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    Delete(s"/api/group/$groupId/admin/${samRoutes.user.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "respond with 404 when the group does not exist" in {
    val samRoutes = createSamRoutesWithResource(resourceTypes, Resource(ManagedGroupService.managedGroupTypeName, ResourceId("foo"), Set.empty))
    Delete(s"/api/group/$groupId/admin/${samRoutes.user.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "respond with 404 when the policy is invalid" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    Delete(s"/api/group/$groupId/people/${samRoutes.user.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "respond with 404 when the requesting user does not have permissions to edit the group and policy" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)


    Delete(s"/api/group/$groupId/admin/${samRoutes.user.email}") ~> newGuy.routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/groups" should "respond with 200 and a list of managed groups the authenticated user belongs to" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    val groupNames = Set("foo", "bar", "baz")

    groupNames.foreach(assertCreateGroup(samRoutes, _))

    Get("/api/groups") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val res = responseAs[String]
      groupNames.foreach(res should include (_))
      res should include ("admin")
      res shouldNot include ("member")
    }
  }

  it should "respond with 200 and an empty list if the user is not a member of any managed groups" in {
    val samRoutes = TestSamRoutes(resourceTypes)


    Get("/api/groups") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual "[]"
    }
  }

  "GET /api/group/{groupName}/admin-notifier" should "succeed with 200 when the group exists and the requesting user is a group admin" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)
    assertGetGroup(samRoutes)

    Get(s"/api/group/$groupId/admin-notifier") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  it should "fail with 404 when the requesting user is a 'member' but not an 'admin'" in {
    val samRoutes: TestSamRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)

    setGroupMembers(samRoutes, Set(newGuy.email), expectedStatus = StatusCodes.Created)

    Get(s"/api/group/$groupId/admin-notifier") ~> newGuy.routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "fail with 404 when the requesting user is not in the group" in {
    withUserNotInGroup(TestSamRoutes(resourceTypes)) { nonMemberRoutes =>
      Get(s"/api/group/$groupId/admin-notifier") ~> nonMemberRoutes.route ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }
  }

  it should "fail with 404 when the group does not exist" in {
    val directoryDAO = new MockDirectoryDAO()
    val policyDao = new MockAccessPolicyDAO(resourceTypes, directoryDAO)
    val samRoutes = TestSamRoutes(resourceTypes, policyAccessDAO = Some(policyDao), maybeDirectoryDAO = Some(directoryDAO))

    policyDao.createResource(Resource(ManagedGroupService.managedGroupTypeName, ResourceId("foo"), Set.empty), samRequestContext).unsafeRunSync()

    Get(s"/api/group/$groupId/admin") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PUT /api/group/{groupName}/admin-notifier" should "succeed with 201 after successfully updating the 'admin-notifier' policy" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)

    Put(s"/api/group/$groupId/admin-notifier", Set(newGuy.email)) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }
  }

  it should "fail with 404 when the requesting user is not in the admin policy for the group" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val newGuy = makeOtherUser(samRoutes)
    Put(s"/api/group/$groupId/admin-notifier", Set(newGuy.email)) ~> newGuy.routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PUT /api/group/{groupName}/accessInstructions" should "succeed with 204" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)
    val instructions = "Test instructions"

    Put(s"/api/group/$groupId/accessInstructions", ManagedGroupAccessInstructions(instructions)) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  "GET /api/group/{groupName}/accessInstructions" should "succeed with 200 and return the access instructions when group and access instructions exist" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)
    val instructions = "Test instructions"

    Put(s"/api/group/$groupId/accessInstructions", ManagedGroupAccessInstructions(instructions)) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/group/$groupId/accessInstructions") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] shouldEqual (instructions)
    }
  }

  it should "succeed with 204 when the group exists but access instructions are not set" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    Get(s"/api/group/$groupId/accessInstructions") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "fail with 404 when the group does not exist" in {
    val samRoutes = TestSamRoutes(resourceTypes)

    Get(s"/api/group/$groupId/accessInstructions") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "POST /api/group/{groupName}/requestAccess" should "succeed with 204 when the group exists" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    Post(s"/api/group/$groupId/requestAccess") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "fail with 400 if there are access instructions" in {
    val samRoutes = TestSamRoutes(resourceTypes)
    assertCreateGroup(samRoutes)

    val instructions = "Test instructions"

    Put(s"/api/group/$groupId/accessInstructions", ManagedGroupAccessInstructions(instructions)) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Post(s"/api/group/$groupId/requestAccess") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
      responseAs[String] should include (instructions)
    }
  }

  it should "fail with 404 when group does not exist" in {
    val samRoutes = createSamRoutesWithResource(resourceTypes, Resource(ManagedGroupService.managedGroupTypeName, ResourceId("foo"), Set.empty))

    Post(s"/api/group/$groupId/requestAccess") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }
}

object ManagedGroupRoutesSpec{
  def createSamRoutesWithResource(resourceTypeMap: Map[ResourceTypeName, ResourceType], resource: Resource)(implicit system: ActorSystem, materializer: Materializer, ec: ExecutionContext): TestSamRoutes ={
    val directoryDAO = new MockDirectoryDAO()
    val policyDao = new MockAccessPolicyDAO(resourceTypeMap, directoryDAO)
    val samRoutes = TestSamRoutes(resourceTypeMap, policyAccessDAO = Some(policyDao), maybeDirectoryDAO = Some(directoryDAO))

    policyDao.createResource(resource, samRequestContext).unsafeRunSync()
    samRoutes
  }
}
