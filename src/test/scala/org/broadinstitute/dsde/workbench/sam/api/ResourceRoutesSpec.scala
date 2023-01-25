package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport.googleServicesConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.{MockAccessPolicyDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.scalatest.AppendedClues
import org.scalatest.matchers.should.Matchers
import spray.json.DefaultJsonProtocol._
import spray.json.{JsBoolean, JsValue}

/** Created by dvoet on 6/7/17.
  */
class ResourceRoutesSpec extends RetryableAnyFlatSpec with Matchers with ScalatestRouteTest with TestSupport with AppendedClues {

  val defaultUserInfo = TestSamRoutes.defaultUserInfo

  private val resourceTypes = TestSupport.appConfig.resourceTypes
  private val resourceTypeMap = resourceTypes.map(rt => rt.name -> rt).toMap
  private val managedGroupResourceType =
    resourceTypeMap.getOrElse(ResourceTypeName("managed-group"), throw new Error("Failed to load managed-group resource type from reference.conf"))

  private val defaultTestUser = Generator.genWorkbenchUserGoogle.sample.get

  private object SamResourceActionPatterns {
    val readPolicies = ResourceActionPattern("read_policies", "", false)
    val alterPolicies = ResourceActionPattern("alter_policies", "", false)
    val delete = ResourceActionPattern("delete", "", false)

    val sharePolicy = ResourceActionPattern("share_policy::.+", "", false)
    val readPolicy = ResourceActionPattern("read_policy::.+", "", false)
    val testActionAccess = ResourceActionPattern("test_action_access::.+", "", false)
  }

  private def createSamRoutes(resourceTypes: Map[ResourceTypeName, ResourceType], samUser: SamUser = defaultUserInfo) = {
    val directoryDAO = new MockDirectoryDAO()
    val accessPolicyDAO = new MockAccessPolicyDAO(resourceTypes, directoryDAO)

    val emailDomain = "example.com"
    val policyEvaluatorService = PolicyEvaluatorService(emailDomain, resourceTypes, accessPolicyDAO, directoryDAO)
    val mockResourceService = new ResourceService(resourceTypes, policyEvaluatorService, accessPolicyDAO, directoryDAO, NoExtensions, emailDomain, Set.empty)
    val tosService = new TosService(directoryDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig)
    val mockUserService = new UserService(directoryDAO, NoExtensions, Seq.empty, tosService)
    val mockStatusService = new StatusService(directoryDAO, NoExtensions, TestSupport.dbRef)
    val mockManagedGroupService =
      new ManagedGroupService(mockResourceService, policyEvaluatorService, resourceTypes, accessPolicyDAO, directoryDAO, NoExtensions, emailDomain)

    TestSupport.runAndWait(mockUserService.createUser(defaultUserInfo, samRequestContext))
    TestSupport.runAndWait(tosService.acceptTosStatus(defaultUserInfo.id, samRequestContext))

    new TestSamRoutes(
      mockResourceService,
      policyEvaluatorService,
      mockUserService,
      mockStatusService,
      mockManagedGroupService,
      samUser,
      directoryDAO,
      tosService = tosService
    )
  }

  "GET /api/resource/{resourceType}/{resourceId}/actions/{action}" should "404 for unknown resource type" in {
    val samRoutes = TestSamRoutes(Map.empty)

    Get("/api/resource/foo/bar/action") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
      responseAs[ErrorReport].message shouldEqual "resource type foo not found"
    }
  }

  "GET /api/resource/{resourceType}/{resourceId}/action/{action}/userEmail/{userEmail}" should "200 if caller have read_policies permission" in {
    // Create a user called userWithEmail and has can_compute on the resource.
    val userWithEmail = Generator.genWorkbenchUserGoogle.sample.get
    val members = AccessPolicyMembership(Set(userWithEmail.email), Set(ResourceAction("can_compute")), Set.empty)
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    val policyName = AccessPolicyName("can_compute")

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))
    runAndWait(samRoutes.userService.createUser(userWithEmail, samRequestContext))
    createUserResourcePolicy(members, resourceType, samRoutes, resourceId, policyName)

    Get(s"/api/resource/${resourceType.name}/${resourceId.value}/action/can_compute/userEmail/${userWithEmail.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[JsValue] shouldEqual JsBoolean(true)
    }
  }

  "GET /api/resource/{resourceType}/{resourceId}/action/{action}/userEmail/{userEmail}" should "200 if caller have testActionAccess::can_compute permission" in {
    // Create a user called userWithEmail and has can_compute on the resource.
    val userWithEmail = Generator.genWorkbenchUserGoogle.sample.get
    val members = AccessPolicyMembership(Set(userWithEmail.email), Set(ResourceAction("can_compute")), Set.empty)
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.testActionAccess, SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.testActionAccess(ResourceAction("can_compute")), SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    val policyName = AccessPolicyName("can_compute")

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))
    runAndWait(samRoutes.userService.createUser(userWithEmail, samRequestContext))
    createUserResourcePolicy(members, resourceType, samRoutes, resourceId, policyName)

    Get(s"/api/resource/${resourceType.name}/${resourceId.value}/action/can_compute/userEmail/${userWithEmail.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[JsValue] shouldEqual JsBoolean(true)
    }

    // Return 403 because the caller doesn't have testActionAccess::alter_policy permission
    Get(s"/api/resource/${resourceType.name}/${resourceId.value}/action/alter_policies/userEmail/${userWithEmail.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "GET /api/resource/{resourceType}/{resourceId}/action/{action}/userEmail/{userEmail}" should "return false if user doesn't have permission or doesn't exist" in {
    // Create a user called userWithEmail but doesn't have can_compute on the resource.
    val userWithEmail = Generator.genWorkbenchUserGoogle.sample.get
    val members = AccessPolicyMembership(Set(userWithEmail.email), Set(ResourceAction("read_policies")), Set.empty)

    // The owner role has read_policies
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    val policyName = AccessPolicyName("can_compute")

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))
    runAndWait(samRoutes.userService.createUser(userWithEmail, samRequestContext))
    createUserResourcePolicy(members, resourceType, samRoutes, resourceId, policyName)

    // The user doesn't have can_compute permission
    Get(s"/api/resource/${resourceType.name}/${resourceId.value}/action/can_compute/userEmail/${userWithEmail.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[JsValue] shouldEqual JsBoolean(false)
    }

    // The user doesn't exist
    Get(s"/api/resource/${resourceType.name}/${resourceId.value}/action/can_compute/userEmail/randomUser") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[JsValue] shouldEqual JsBoolean(false)
    }
  }

  "GET /api/resource/{resourceType}/{resourceId}/action/{action}/userEmail/{userEmail}" should "return 403 if caller doesn't have permission" in {
    // Create a user called userWithEmail and have can_compute on the resource.
    val userWithEmail = Generator.genWorkbenchUserGoogle.sample.get
    val members = AccessPolicyMembership(Set(userWithEmail.email), Set(ResourceAction("read_policies")), Set.empty)

    // The owner role only have alert_policies
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    val policyName = AccessPolicyName("can_compute")

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))
    runAndWait(samRoutes.userService.createUser(userWithEmail, samRequestContext))
    createUserResourcePolicy(members, resourceType, samRoutes, resourceId, policyName)

    // The user doesn't have can_compute permission
    Get(s"/api/resource/${resourceType.name}/${resourceId.value}/action/can_compute/userEmail/${userWithEmail.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "GET /api/resourceTypes" should "200 when listing all resource types" in {
    val samRoutes = TestSamRoutes(Map.empty)

    Get("/api/resourceTypes") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  "POST /api/resource/{resourceType}" should "204 create resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(ResourceActionPattern("run", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val createResourceRequest = CreateResourceRequest(
      ResourceId("foo"),
      Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.email), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))),
      Set.empty
    )
    Post(s"/api/resource/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/resource/${resourceType.name}/foo/action/run") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[JsValue] shouldEqual JsBoolean(true)
    }
  }

  it should "400 for resourceTypeAdmin" in {
    val samRoutes = TestSamRoutes(Map.empty)

    val createResourceRequest = CreateResourceRequest(
      ResourceId("foo"),
      Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.email), Set(ResourceAction("run")), Set.empty)),
      Set.empty
    )
    Post(s"/api/resource/${SamResourceTypes.resourceTypeAdminName}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "204 when valid auth domain is provided and the resource type is constrainable" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(ResourceActionPattern("run", "", true)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType, managedGroupResourceType.name -> managedGroupResourceType))

    resourceType.isAuthDomainConstrainable shouldEqual true

    val authDomainId = ResourceId("myAuthDomain")
    runAndWait(samRoutes.managedGroupService.createManagedGroup(authDomainId, defaultUserInfo, samRequestContext = samRequestContext))
    val authDomain = Set(WorkbenchGroupName(authDomainId.value))

    val createResourceRequest = CreateResourceRequest(
      ResourceId("foo"),
      Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.email), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))),
      authDomain
    )
    Post(s"/api/resource/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/resource/${resourceType.name}/foo/action/run") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[JsValue] shouldEqual JsBoolean(true)
    }
  }

  it should "400 when resource type allows auth domains and id reuse" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(ResourceActionPattern("run", "", true)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))),
      ResourceRoleName("owner"),
      true
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val createResourceRequest = CreateResourceRequest(
      ResourceId("foo"),
      Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.email), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))),
      Set.empty
    )
    Post(s"/api/resource/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "400 when no policies are provided" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(ResourceActionPattern("run", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val createResourceRequest = CreateResourceRequest(ResourceId("foo"), Map.empty, Set.empty)
    Post(s"/api/resource/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "400 when auth domain group does not exist" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(ResourceActionPattern("run", "", true)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))),
      ResourceRoleName("owner")
    )
    val authDomainId = ResourceId("myAuthDomain")
    val samRoutes = ManagedGroupRoutesSpec.createSamRoutesWithResource(
      Map(resourceType.name -> resourceType, managedGroupResourceType.name -> managedGroupResourceType),
      Resource(ManagedGroupService.managedGroupTypeName, authDomainId, Set.empty)
    )
    resourceType.isAuthDomainConstrainable shouldEqual true

    val authDomain = Set(WorkbenchGroupName(authDomainId.value))
    // Group is never persisted

    val createResourceRequest = CreateResourceRequest(
      ResourceId("foo"),
      Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.email), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))),
      authDomain
    )
    Post(s"/api/resource/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "400 when auth domain group exists but requesting user is not in that group" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(ResourceActionPattern("run", "", true)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType, managedGroupResourceType.name -> managedGroupResourceType))

    resourceType.isAuthDomainConstrainable shouldEqual true

    val authDomainId = ResourceId("myAuthDomain")
    val otherUser = Generator.genWorkbenchUserGoogle.sample.get
    runAndWait(samRoutes.userService.createUser(otherUser, samRequestContext))
    runAndWait(samRoutes.managedGroupService.createManagedGroup(authDomainId, otherUser, samRequestContext = samRequestContext))
    val authDomain = Set(WorkbenchGroupName(authDomainId.value))

    val createResourceRequest = CreateResourceRequest(
      ResourceId("foo"),
      Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.email), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))),
      authDomain
    )
    Post(s"/api/resource/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  "POST /api/resource/{resourceType}/{resourceId}" should "204 create resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(ResourceActionPattern("run", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/resource/${resourceType.name}/foo/action/run") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[JsValue] shouldEqual JsBoolean(true)
    }

  }

  "GET /api/resource/{resourceType}/{resourceId}/roles" should "200 on list resource roles" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(ResourceActionPattern("run", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/resource/${resourceType.name}/foo/roles") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[String]]
    }
  }

  it should "404 on list resource roles when resource type doesnt exist" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(ResourceActionPattern("run", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Get(s"/api/resource/doesntexist/foo/roles") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/resource/{resourceType}/{resourceId}/policies/{policyName}" should "200 on existing policy of a resource with read_policies" in {
    val members = AccessPolicyMembership(Set(defaultUserInfo.email), Set.empty, Set.empty)
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    val policyName = AccessPolicyName("bar")
    createUserResourcePolicy(members, resourceType, samRoutes, resourceId, policyName)

    Get(s"/api/resource/${resourceType.name}/${resourceId.value}/policies/${policyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[AccessPolicyMembership] shouldEqual members
    }
  }

  private def responsePayloadClue(str: String): String = s" -> Here is the response payload: $str"

  private def createUserResourcePolicy(
      members: AccessPolicyMembership,
      resourceType: ResourceType,
      samRoutes: TestSamRoutes,
      resourceId: ResourceId,
      policyName: AccessPolicyName
  ): Unit = {
    findOrCreateUser(samRoutes.user, samRoutes.userService)

    Post(s"/api/resource/${resourceType.name}/${resourceId.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent withClue responsePayloadClue(responseAs[String])
    }

    Put(s"/api/resource/${resourceType.name}/${resourceId.value}/policies/${policyName.value}", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created withClue responsePayloadClue(responseAs[String])
    }
  }

  private def findOrCreateUser(user: SamUser, userService: UserService): UserStatus =
    runAndWait(userService.getUserStatus(user.id, samRequestContext = samRequestContext)) match {
      case Some(userStatus) => userStatus
      case None => runAndWait(userService.createUser(user, samRequestContext))
    }

  it should "200 on existing policy of a resource with read_policy" in {
    val members = AccessPolicyMembership(Set(defaultUserInfo.email), Set.empty, Set.empty)
    val policyName = AccessPolicyName("bar")
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicy),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicy(policyName)))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    createUserResourcePolicy(members, resourceType, samRoutes, resourceId, policyName)

    Get(s"/api/resource/${resourceType.name}/${resourceId.value}/policies/${policyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[AccessPolicyMembership] shouldEqual members
    }
  }

  it should "404 on non existing policy of a resource" in {
    val members = AccessPolicyMembership(Set(defaultUserInfo.email), Set.empty, Set.empty)
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    val policyName = AccessPolicyName("bar")
    createUserResourcePolicy(members, resourceType, samRoutes, resourceId, policyName)

    Get(s"/api/resource/${resourceType.name}/${resourceId.value}/policies/${policyName.value}_dne") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "403 on existing policy of a resource without read policies" in {
    val members = AccessPolicyMembership(Set(defaultUserInfo.email), Set.empty, Set.empty)
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    val policyName = AccessPolicyName("bar")
    createUserResourcePolicy(members, resourceType, samRoutes, resourceId, policyName)

    Get(s"/api/resource/${resourceType.name}/${resourceId.value}/policies/${policyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "PUT /api/resource/{resourceType}/{resourceId}/policies/{policyName}" should "201 on a new policy being created for a resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    val members = AccessPolicyMembership(Set(defaultTestUser.email), Set(ResourceAction("can_compute")), Set.empty)

    Put(s"/api/resource/${resourceType.name}/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }
  }

  it should "201 on a policy being updated" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    val members = AccessPolicyMembership(Set(defaultTestUser.email), Set(ResourceAction("can_compute")), Set.empty)

    Put(s"/api/resource/${resourceType.name}/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    val members2 = AccessPolicyMembership(Set(defaultTestUser.email), Set(ResourceAction("can_compute")), Set(ResourceRoleName("owner")))

    Put(s"/api/resource/${resourceType.name}/foo/policies/canCompute", members2) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }
  }

  it should "204 on overwriting a policy's membership" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    val members = AccessPolicyMembership(Set(defaultTestUser.email), Set(ResourceAction("can_compute")), Set.empty)

    Put(s"/api/resource/${resourceType.name}/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    val members2 = Set(defaultTestUser.email)

    Put(s"/api/resource/${resourceType.name}/foo/policies/canCompute/memberEmails", members2) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "400 on a policy being created with invalid actions" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    val fakeActions = Set(ResourceAction("fake_action1"), ResourceAction("other_fake_action"))
    val members = AccessPolicyMembership(Set(defaultTestUser.email), fakeActions, Set.empty)

    Put(s"/api/resource/${resourceType.name}/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
      fakeActions foreach { action => responseAs[String] should include(action.value) }
      responseAs[String] should include("invalid action")
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "400 on a policy being created with invalid roles" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    val fakeRoles = Set(ResourceRoleName("fakerole"), ResourceRoleName("otherfakerole"))
    val members = AccessPolicyMembership(Set(defaultTestUser.email), Set(ResourceAction("can_compute")), fakeRoles)

    Put(s"/api/resource/${resourceType.name}/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
      fakeRoles foreach { role => responseAs[String] should include(role.value) }
      responseAs[String] should include("invalid role")
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "400 on a policy being created with invalid member emails" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    val badEmail = WorkbenchEmail("null@bar.baz")
    val nonExistingMembers = AccessPolicyMembership(Set(badEmail), Set(ResourceAction("can_compute")), Set(ResourceRoleName("owner")))

    Put(s"/api/resource/${resourceType.name}/foo/policies/canCompute", nonExistingMembers) ~> samRoutes.route ~> check {
      responseAs[String] shouldNot include(defaultTestUser.email.value)
      responseAs[String] should include(badEmail.value)
      responseAs[String] should include("invalid member email")
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "403 when creating a policy on a resource when the user doesn't have alter_policies permission (but can see the resource)" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("can_compute")))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    val members = AccessPolicyMembership(Set(WorkbenchEmail("me@me.me")), Set(ResourceAction("can_compute")), Set.empty)

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Put(s"/api/resource/${resourceType.name}/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when creating a policy on a resource type that doesnt exist" in {
    val samRoutes = TestSamRoutes(Map.empty)
    val members = AccessPolicyMembership(Set(WorkbenchEmail("foo@bar.baz")), Set(ResourceAction("can_compute")), Set.empty)

    // Create a resource of a type that doesn't exist
    Put(s"/api/resource/fakeresourcetype/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 when creating a policy on a resource that the user doesnt have permission to see" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )
    val directoryDAO = new MockDirectoryDAO()
    val policyDao = new MockAccessPolicyDAO(resourceTypes, directoryDAO)
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType), policyAccessDAO = Some(policyDao), maybeDirectoryDAO = Some(directoryDAO))

    val otherUserSamRoutes = TestSamRoutes(
      Map(resourceType.name -> resourceType),
      Generator.genWorkbenchUserGoogle.sample.get,
      policyAccessDAO = Some(policyDao),
      maybeDirectoryDAO = Some(directoryDAO)
    )
    policyDao.createResource(Resource(ResourceTypeName("rt"), ResourceId("foo"), Set.empty), samRequestContext).unsafeRunSync()
    val members = AccessPolicyMembership(Set(WorkbenchEmail("foo@bar.baz")), Set(ResourceAction("can_compute")), Set.empty)

    // Create a resource
    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    // As a different user who isn't on any policy, try to overwrite a policy
    Put(s"/api/resource/${resourceType.name}/foo/policies/canCompute", members) ~> otherUserSamRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/resource/{resourceType}/{resourceId}/policies" should "200 when listing policies for a resource and user has read_policies permission" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    // Create a resource
    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    // Read the policies
    Get(s"/api/resource/${resourceType.name}/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  it should "403 when listing policies for a resource and user lacks read_policies permission (but can see the resource)" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    // Create a resource that doesn't have the read_policies action on any roles
    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    // Try to read the policies
    Get(s"/api/resource/${resourceType.name}/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when listing policies for a resource type that doesnt exist" in {
    val samRoutes = TestSamRoutes(Map.empty)

    // List policies for a bogus resource type
    Get(s"/api/resource/fakeresourcetype/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 when listing policies for a resource when user can't see the resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )
    val directoryDAO = new MockDirectoryDAO()
    val policyDao = new MockAccessPolicyDAO(resourceTypes, directoryDAO)
    val samRoutes =
      ManagedGroupRoutesSpec.createSamRoutesWithResource(Map(resourceType.name -> resourceType), Resource(resourceType.name, ResourceId("foo"), Set.empty))
    val otherUserSamRoutes = TestSamRoutes(
      Map(resourceType.name -> resourceType),
      Generator.genWorkbenchUserGoogle.sample.get,
      policyAccessDAO = Some(policyDao),
      maybeDirectoryDAO = Some(directoryDAO)
    )
    policyDao.createResource(Resource(resourceType.name, ResourceId("foo"), Set.empty), samRequestContext).unsafeRunSync()
    // Create the resource
    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    // As a different user, try to read the policies
    Get(s"/api/resource/${resourceType.name}/foo/policies") ~> otherUserSamRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "DELETE /api/resource/{resourceType}/{resourceId}" should "204 when deleting a resource and the user has permission to do so" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies, SamResourceActionPatterns.delete),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.delete, SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    // Create the resource
    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    // Read the policies to make sure the resource exists)
    Get(s"/api/resource/${resourceType.name}/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    // Delete the resource
    Delete(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "403 when deleting a resource and the user has permission to see the resource but not delete" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    // Create the resource
    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    // Read the policies to make sure the resource exists)
    Get(s"/api/resource/${resourceType.name}/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    // Delete the resource
    Delete(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when deleting a resource of a type that doesn't exist" in {
    val samRoutes = TestSamRoutes(Map.empty)

    // Delete the resource
    Delete(s"/api/resource/INVALID_RESOURCE_TYPE/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 when deleting a resource that exists but can't be seen by the user" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))),
      ResourceRoleName("owner")
    )
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    samRoutes.resourceService.createResourceType(resourceType, samRequestContext).unsafeRunSync()
    val user = Generator.genWorkbenchUserGoogle.sample.get
    runAndWait(samRoutes.userService.createUser(user, samRequestContext))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), user, samRequestContext))

    // Verify resource exists by checking for conflict on recreate
    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }

    // Delete the resource
    Delete(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/resource/{resourceType}" should "200" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    // Create a resource
    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    // Read the policies
    Get(s"/api/resource/${resourceType.name}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  "PUT /api/resource/{resourceType}/{resourceId}/policies/{policyName}/memberEmails/{email}" should "204 adding a member" in {
    // happy case
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), defaultUserInfo, samRequestContext))

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    Put(s"/api/resource/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "204 adding a member with can share" in {
    // happy case
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.sharePolicy),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.sharePolicy(AccessPolicyName("owner"))))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), defaultUserInfo, samRequestContext))

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    Put(s"/api/resource/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "404 when the policy does not exist" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )

    val resourceId = ResourceId("foo")
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, defaultUserInfo, samRequestContext))
    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    Put(s"/api/resource/${resourceType.name}/$resourceId/policies/does-not-exist/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "400 adding unknown subject" in {
    // differs from happy case in that we don't create user
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), defaultUserInfo, samRequestContext))

    // runAndWait(samRoutes.userService.createUser(testUser))

    Put(s"/api/resource/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "403 adding without permission" in {
    // differs from happy case in that owner role does not have alter_policies
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.sharePolicy),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.sharePolicy(AccessPolicyName("splat"))))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), defaultUserInfo, samRequestContext))

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    Put(s"/api/resource/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 adding without any access" in {
    // differs from happy case in that testUser creates resource, not defaultUser which calls the PUT
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("can_compute")))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    val testUser = Generator.genWorkbenchUserGoogle.sample.get

    runAndWait(samRoutes.userService.createUser(testUser, samRequestContext))

    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), testUser, samRequestContext))

    Put(s"/api/resource/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${testUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "DELETE /api/resource/{resourceType}/{resourceId}/policies/{policyName}/memberEmails/{email}" should "204 deleting a member" in {
    // happy case
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), defaultUserInfo, samRequestContext))

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    runAndWait(
      samRoutes.resourceService.addSubjectToPolicy(
        FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceType.name, ResourceId("foo")), AccessPolicyName(resourceType.ownerRoleName.value)),
        defaultTestUser.id,
        samRequestContext
      )
    )

    Delete(s"/api/resource/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "204 deleting a member with can share" in {
    // happy case
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.sharePolicy, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.sharePolicy(AccessPolicyName("owner"))))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), defaultUserInfo, samRequestContext))

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    runAndWait(
      samRoutes.resourceService.addSubjectToPolicy(
        FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceType.name, ResourceId("foo")), AccessPolicyName(resourceType.ownerRoleName.value)),
        defaultTestUser.id,
        samRequestContext
      )
    )

    Delete(s"/api/resource/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "400 deleting unknown subject" in {
    // differs from happy case in that we don't create user
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), defaultUserInfo, samRequestContext))

    // runAndWait(samRoutes.userService.createUser(testUser))

    // runAndWait(samRoutes.resourceService.addSubjectToPolicy(ResourceAndPolicyName(Resource(resourceType.name,  ResourceId("foo")), AccessPolicyName(resourceType.ownerRoleName.value)), testUser.id))

    Delete(s"/api/resource/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "403 removing without permission" in {
    // differs from happy case in that owner role does not have alter_policies
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.sharePolicy),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.sharePolicy(AccessPolicyName("splat"))))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), defaultUserInfo, samRequestContext))

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    runAndWait(
      samRoutes.resourceService.addSubjectToPolicy(
        FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceType.name, ResourceId("foo")), AccessPolicyName(resourceType.ownerRoleName.value)),
        defaultTestUser.id,
        samRequestContext
      )
    )

    Delete(s"/api/resource/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 removing without any access" in {
    // differs from happy case in that testUser creates resource, not defaultUser which calls the PUT
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("can_compute")))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    val testUser = Generator.genWorkbenchUserGoogle.sample.get

    runAndWait(samRoutes.userService.createUser(testUser, samRequestContext))

    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), testUser, samRequestContext))

    runAndWait(
      samRoutes.resourceService.addSubjectToPolicy(
        FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceType.name, ResourceId("foo")), AccessPolicyName(resourceType.ownerRoleName.value)),
        testUser.id,
        samRequestContext
      )
    )

    Delete(s"/api/resource/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${testUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 when the policy does not exist" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )

    val resourceId = ResourceId("foo")
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, defaultUserInfo, samRequestContext))
    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    Delete(s"/api/resource/${resourceType.name}/$resourceId/policies/does-not-exist/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }
}
