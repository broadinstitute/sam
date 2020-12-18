package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport.{configResourceTypes, genGoogleSubjectId}
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes.SamResourceActionPatterns
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.RootPrimitiveJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.MockAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.{TestSupport, model}
import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers.{any, argThat, eq => mockitoEq}
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.AppendedClues
import spray.json.DefaultJsonProtocol._
import spray.json.{JsBoolean, JsValue}

import scala.concurrent.Future
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ResourceRoutesV2Spec extends AnyFlatSpec with Matchers with TestSupport with ScalatestRouteTest with AppendedClues with MockitoSugar {

  implicit val errorReportSource = ErrorReportSource("sam")

  val defaultUserInfo = UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)

  val defaultResourceType = ResourceType(
    ResourceTypeName("rt"),
    Set.empty,
    Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
    ResourceRoleName("owner")
  )

  private def createSamRoutes(resourceTypes: Map[ResourceTypeName, ResourceType] = Map(defaultResourceType.name -> defaultResourceType),
                              userInfo: UserInfo = defaultUserInfo): SamRoutes = {
    val accessPolicyDAO = new MockAccessPolicyDAO(resourceTypes)
    val directoryDAO = new MockDirectoryDAO()
    val registrationDAO = new MockDirectoryDAO()
    val emailDomain = "example.com"

    val policyEvaluatorService = mock[PolicyEvaluatorService](RETURNS_SMART_NULLS)
    val mockResourceService = mock[ResourceService](RETURNS_SMART_NULLS)
    resourceTypes.map { case (resourceTypeName, resourceType) =>
      when(mockResourceService.getResourceType(resourceTypeName)).thenReturn(IO(Option(resourceType)))
    }
    val mockUserService = new UserService(directoryDAO, NoExtensions, registrationDAO, Seq.empty)
    val mockStatusService = new StatusService(directoryDAO, NoExtensions, TestSupport.dbRef)
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, policyEvaluatorService, resourceTypes, accessPolicyDAO, directoryDAO, NoExtensions, emailDomain)

    mockUserService.createUser(CreateWorkbenchUser(defaultUserInfo.userId, genGoogleSubjectId(), defaultUserInfo.userEmail, None), samRequestContext)

    new TestSamRoutes(mockResourceService, policyEvaluatorService, mockUserService, mockStatusService, mockManagedGroupService, userInfo, directoryDAO)
  }

  private val managedGroupResourceType = configResourceTypes.getOrElse(ResourceTypeName("managed-group"), throw new Error("Failed to load managed-group resource type from reference.conf"))

  private val defaultTestUser =  CreateWorkbenchUser(WorkbenchUserId("testuser"), genGoogleSubjectId(), WorkbenchEmail("testuser@foo.com"), None)

  "GET /api/resources/v2/{resourceType}/{resourceId}/actions/{action}" should "404 for unknown resource type" in {
    val samRoutes = TestSamRoutes(Map.empty)

    Get("/api/resources/v2/foo/bar/action") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
      responseAs[ErrorReport].message shouldEqual "resource type foo not found"
    }
  }

  "GET /api/config/v1/resourceTypes" should "200 when listing all resource types" in {
    val samRoutes = TestSamRoutes(Map.empty)

    Get("/api/config/v1/resourceTypes") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  "POST /api/resources/v2/{resourceType}" should "204 create resource" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceActionPattern("run", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val createResourceRequest = CreateResourceRequest(ResourceId("foo"), Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))), Set.empty)
    Post(s"/api/resources/v2/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/resources/v2/${resourceType.name}/foo/action/run") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[JsValue] shouldEqual JsBoolean(true)
    }
  }

  "POST /api/resources/v2/{resourceType} with returnResource = true" should "201 create resource with content" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceActionPattern("run", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val createResourceRequest = CreateResourceRequest(ResourceId("foo"), Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))), Set.empty, Some(true))
    Post(s"/api/resources/v2/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      val r = responseAs[CreateResourceResponse]

      r.resourceId shouldEqual createResourceRequest.resourceId
      r.authDomain shouldEqual createResourceRequest.authDomain
      r.resourceTypeName shouldEqual resourceType.name

      val returnedNames = r.accessPolicies.map( x => x.id.accessPolicyName )
      createResourceRequest.policies.keys.foreach { k =>
        returnedNames.contains(k) shouldEqual true
      }
    }
  }

  "POST /api/resources/v2/{resourceType} with returnResource = false" should "204 create resource with content" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceActionPattern("run", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val createResourceRequest = CreateResourceRequest(ResourceId("foo"), Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))), Set.empty, Some(false))
    Post(s"/api/resources/v2/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "204 create resource with content with parent" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceActionPattern(SamResourceActions.setParent.value, "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.setParent, SamResourceActions.addChild))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val createParentResourceRequest = CreateResourceRequest(ResourceId("parent"), Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set.empty, Set(resourceType.ownerRoleName))), Set.empty, Some(false))
    Post(s"/api/resources/v2/${resourceType.name}", createParentResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    val createResourceRequest = CreateResourceRequest(ResourceId("foo"), Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set.empty, Set(resourceType.ownerRoleName))), Set.empty, Some(false), Some(FullyQualifiedResourceId(resourceType.name, createParentResourceRequest.resourceId)))
    Post(s"/api/resources/v2/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "400 with parent when parents not allowed" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceActionPattern(SamResourceActions.setParent.value, "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.addChild))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val createParentResourceRequest = CreateResourceRequest(ResourceId("parent"), Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set.empty, Set(resourceType.ownerRoleName))), Set.empty, Some(false))
    Post(s"/api/resources/v2/${resourceType.name}", createParentResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    val createResourceRequest = CreateResourceRequest(ResourceId("foo"), Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set.empty, Set(resourceType.ownerRoleName))), Set.empty, Some(false), Some(FullyQualifiedResourceId(resourceType.name, createParentResourceRequest.resourceId)))
    Post(s"/api/resources/v2/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "403 with parent when add_child not allowed on parent" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceActionPattern(SamResourceActions.setParent.value, "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.setParent))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val createParentResourceRequest = CreateResourceRequest(ResourceId("parent"), Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set.empty, Set(resourceType.ownerRoleName))), Set.empty, Some(false))
    Post(s"/api/resources/v2/${resourceType.name}", createParentResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    val createResourceRequest = CreateResourceRequest(ResourceId("foo"), Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set.empty, Set(resourceType.ownerRoleName))), Set.empty, Some(false), Some(FullyQualifiedResourceId(resourceType.name, createParentResourceRequest.resourceId)))
    Post(s"/api/resources/v2/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "403 with parent when parent does not exist" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceActionPattern(SamResourceActions.setParent.value, "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.setParent))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val createResourceRequest = CreateResourceRequest(ResourceId("foo"), Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set.empty, Set(resourceType.ownerRoleName))), Set.empty, Some(false), Some(FullyQualifiedResourceId(resourceType.name, ResourceId("parent"))))
    Post(s"/api/resources/v2/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "204 when valid auth domain is provided and the resource type is constrainable" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceActionPattern("run", "", true)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType, managedGroupResourceType.name -> managedGroupResourceType))

    resourceType.isAuthDomainConstrainable shouldEqual true

    val authDomainId = ResourceId("myAuthDomain")
    runAndWait(samRoutes.managedGroupService.createManagedGroup(authDomainId, defaultUserInfo, samRequestContext = samRequestContext))
    val authDomain = Set(WorkbenchGroupName(authDomainId.value))

    val createResourceRequest = CreateResourceRequest(ResourceId("foo"), Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))), authDomain)
    Post(s"/api/resources/v2/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/resources/v2/${resourceType.name}/foo/action/run") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[JsValue] shouldEqual JsBoolean(true)
    }
  }

  it should "400 when resource type allows auth domains and id reuse" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceActionPattern("run", "", true)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"), true)
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val createResourceRequest = CreateResourceRequest(ResourceId("foo"), Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))), Set.empty)
    Post(s"/api/resources/v2/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "400 when no policies are provided" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceActionPattern("run", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val createResourceRequest = CreateResourceRequest(ResourceId("foo"), Map.empty, Set.empty)
    Post(s"/api/resources/v2/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "400 when auth domain group does not exist" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceActionPattern("run", "", true)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))

    val authDomainId = ResourceId("myAuthDomain")
    val samRoutes = ManagedGroupRoutesSpec.createSamRoutesWithResource(Map(resourceType.name -> resourceType, managedGroupResourceType.name -> managedGroupResourceType), Resource(ManagedGroupService.managedGroupTypeName, authDomainId, Set.empty))

    resourceType.isAuthDomainConstrainable shouldEqual true

    val authDomain = Set(WorkbenchGroupName(authDomainId.value))
    // Group is never persisted

    val createResourceRequest = CreateResourceRequest(ResourceId("foo"), Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))), authDomain)
    Post(s"/api/resources/v2/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "400 when auth domain group exists but requesting user is not in that group" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceActionPattern("run", "", true)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType, managedGroupResourceType.name -> managedGroupResourceType))

    resourceType.isAuthDomainConstrainable shouldEqual true

    val authDomainId = ResourceId("myAuthDomain")
    val otherUser = UserInfo(OAuth2BearerToken("magicString"), WorkbenchUserId("bugsBunny"), WorkbenchEmail("bugsford_bunnington@example.com"), 0)
    runAndWait(samRoutes.userService.createUser(CreateWorkbenchUser(otherUser.userId, genGoogleSubjectId(), otherUser.userEmail, None), samRequestContext))
    runAndWait(samRoutes.managedGroupService.createManagedGroup(authDomainId, otherUser, samRequestContext = samRequestContext))
    val authDomain = Set(WorkbenchGroupName(authDomainId.value))

    val createResourceRequest = CreateResourceRequest(ResourceId("foo"), Map(AccessPolicyName("goober") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))), authDomain)
    Post(s"/api/resources/v2/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  "POST /api/resources/v2/{resourceType}/{resourceId}" should "204 create resource" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceActionPattern("run", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resources/v2/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/resources/v2/${resourceType.name}/foo/action/run") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[JsValue] shouldEqual JsBoolean(true)
    }

  }

  "GET /api/resources/v2/{resourceType}/{resourceId}/roles" should "200 on list resource roles" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceActionPattern("run", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resources/v2/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/resources/v2/${resourceType.name}/foo/roles") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[String]]
    }
  }

  it should "404 on list resource roles when resource type doesnt exist" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceActionPattern("run", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Get(s"/api/resources/v2/doesntexist/foo/roles") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/resources/v2/{resourceType}/{resourceId}/actions" should "200 on list resource actions" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceActionPattern("run", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resources/v2/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/resources/v2/${resourceType.name}/foo/actions") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[String]]
    }
  }

  it should "404 on list resource actions when resource type doesnt exist" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceActionPattern("run", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Get(s"/api/resources/v2/doesntexist/foo/actions") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  private def responsePayloadClue(str: String): String = s" -> Here is the response payload: $str"

  private def createUserResourcePolicy(members: AccessPolicyMembership, resourceType: ResourceType, samRoutes: TestSamRoutes, resourceId: ResourceId, policyName: AccessPolicyName): Unit = {
    val user = CreateWorkbenchUser(samRoutes.userInfo.userId, genGoogleSubjectId(), samRoutes.userInfo.userEmail, None)
    findOrCreateUser(user, samRoutes.userService)

    Post(s"/api/resources/v2/${resourceType.name}/${resourceId.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent withClue responsePayloadClue(responseAs[String])
    }


    Put(s"/api/resources/v2/${resourceType.name}/${resourceId.value}/policies/${policyName.value}", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created withClue responsePayloadClue(responseAs[String])
    }
  }

  private def findOrCreateUser(user: CreateWorkbenchUser, userService: UserService): UserStatus = {
    runAndWait(userService.getUserStatus(user.id, samRequestContext = samRequestContext)) match {
      case Some(userStatus) => userStatus
      case None => runAndWait(userService.createUser(user, samRequestContext))
    }
  }

  "DELETE /api/resources/v2/{resourceType}/{resourceId}" should "204 when deleting a resource and the user has permission to do so" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies, SamResourceActionPatterns.delete), Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.delete, SamResourceActions.readPolicies))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    //Create the resource
    Post(s"/api/resources/v2/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    //Read the policies to make sure the resource exists)
    Get(s"/api/resources/v2/${resourceType.name}/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    //Delete the resource
    Delete(s"/api/resources/v2/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "403 when deleting a resource and the user has permission to see the resource but not delete" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies), Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicies))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    //Create the resource
    Post(s"/api/resources/v2/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    //Read the policies to make sure the resource exists)
    Get(s"/api/resources/v2/${resourceType.name}/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    //Delete the resource
    Delete(s"/api/resources/v2/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when deleting a resource of a type that doesn't exist" in {
    val samRoutes = TestSamRoutes(Map.empty)

    //Delete the resource
    Delete(s"/api/resources/v2/INVALID_RESOURCE_TYPE/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 when deleting a resource that exists but can't be seen by the user" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    samRoutes.resourceService.createResourceType(resourceType, samRequestContext).unsafeRunSync()
    runAndWait(samRoutes.userService.createUser(CreateWorkbenchUser(WorkbenchUserId("user2"), genGoogleSubjectId(), WorkbenchEmail("user2@example.com"), None), samRequestContext))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user2"), WorkbenchEmail("user2@example.com"), 0), samRequestContext))

    //Verify resource exists by checking for conflict on recreate
    Post(s"/api/resources/v2/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }

    //Delete the resource
    Delete(s"/api/resources/v2/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/resources/v2/{resourceType}" should "200" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(SamResourceActionPatterns.readPolicies, ResourceActionPattern("can_compute", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicies))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    //Create a resource
    Post(s"/api/resources/v2/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    //Read the policies
    Get(s"/api/resources/v2/${resourceType.name}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[List[UserResourcesResponse]].size should equal(1)
    }
  }

  "PUT /api/resources/v2/{resourceType}/{resourceId}/policies/{policyName}/memberEmails/{email}" should "204 adding a member" in {
    // happy case
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), defaultUserInfo, samRequestContext))

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    Put(s"/api/resources/v2/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "204 adding a member with can share" in {
    // happy case
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.sharePolicy),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.sharePolicy(AccessPolicyName("owner"))))),
      ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), defaultUserInfo, samRequestContext))

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    Put(s"/api/resources/v2/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "400 adding unknown subject" in {
    // differs from happy case in that we don't create user
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), defaultUserInfo, samRequestContext))

    //runAndWait(samRoutes.userService.createUser(testUser))

    Put(s"/api/resources/v2/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "403 adding without permission" in {
    // differs from happy case in that owner role does not have alter_policies
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.sharePolicy), Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.sharePolicy(AccessPolicyName("splat"))))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), defaultUserInfo, samRequestContext))

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    Put(s"/api/resources/v2/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 adding without any access" in {
    // differs from happy case in that testUser creates resource, not defaultUser which calls the PUT
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("can_compute")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    val testUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("testuser"), WorkbenchEmail("testuser@foo.com"), 0)

    runAndWait(samRoutes.userService.createUser(CreateWorkbenchUser(testUser.userId, genGoogleSubjectId(), testUser.userEmail, None), samRequestContext))

    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), testUser, samRequestContext))

    Put(s"/api/resources/v2/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${testUser.userEmail}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "DELETE /api/resources/v2/{resourceType}/{resourceId}/policies/{policyName}/memberEmails/{email}" should "204 deleting a member" in {
    // happy case
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), defaultUserInfo, samRequestContext))

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    runAndWait(samRoutes.resourceService.addSubjectToPolicy(FullyQualifiedPolicyId(
      FullyQualifiedResourceId(resourceType.name,  ResourceId("foo")), AccessPolicyName(resourceType.ownerRoleName.value)), defaultTestUser.id, samRequestContext))

    Delete(s"/api/resources/v2/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "204 deleting a member with can share" in {
    // happy case
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(SamResourceActionPatterns.sharePolicy, ResourceActionPattern("can_compute", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.sharePolicy(AccessPolicyName("owner"))))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), defaultUserInfo, samRequestContext))

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    runAndWait(samRoutes.resourceService.addSubjectToPolicy(FullyQualifiedPolicyId(
      FullyQualifiedResourceId(resourceType.name,  ResourceId("foo")), AccessPolicyName(resourceType.ownerRoleName.value)), defaultTestUser.id, samRequestContext))

    Delete(s"/api/resources/v2/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "400 deleting unknown subject" in {
    // differs from happy case in that we don't create user
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), defaultUserInfo, samRequestContext))

    //runAndWait(samRoutes.userService.createUser(testUser))

    //runAndWait(samRoutes.resourceService.addSubjectToPolicy(ResourceAndPolicyName(Resource(resourceType.name,  ResourceId("foo")), AccessPolicyName(resourceType.ownerRoleName.value)), testUser.id))

    Delete(s"/api/resources/v2/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "403 removing without permission" in {
    // differs from happy case in that owner role does not have alter_policies
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.sharePolicy), Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.sharePolicy(AccessPolicyName("splat"))))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), defaultUserInfo, samRequestContext))

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    runAndWait(samRoutes.resourceService.addSubjectToPolicy(FullyQualifiedPolicyId(
      FullyQualifiedResourceId(resourceType.name,  ResourceId("foo")), AccessPolicyName(resourceType.ownerRoleName.value)), defaultTestUser.id, samRequestContext))

    Delete(s"/api/resources/v2/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 removing without any access" in {
    // differs from happy case in that testUser creates resource, not defaultUser which calls the PUT
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("can_compute")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    val testUser = UserInfo(OAuth2BearerToken("token"), WorkbenchUserId("testuser"), WorkbenchEmail("testuser@foo.com"), 0)

    runAndWait(samRoutes.userService.createUser(CreateWorkbenchUser(testUser.userId, genGoogleSubjectId(), testUser.userEmail, None), samRequestContext))

    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), testUser, samRequestContext))

    runAndWait(samRoutes.resourceService.addSubjectToPolicy(FullyQualifiedPolicyId(
      FullyQualifiedResourceId(resourceType.name,  ResourceId("foo")), AccessPolicyName(resourceType.ownerRoleName.value)), testUser.userId, samRequestContext))

    Delete(s"/api/resources/v2/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${testUser.userEmail}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/resources/v2/{resourceType}/{resourceId}/policies/{policyName}/public" should "200 if user has read_policies" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicies))),
      ResourceRoleName("owner"))

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.userInfo, samRequestContext))

    Get(s"/api/resources/v2/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/public") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK withClue responsePayloadClue(responseAs[String])
      responseAs[Boolean] should equal(false)
    }
  }

  it should "200 if user has read_policy::" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readPolicy),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicy(AccessPolicyName("owner"))))),
      ResourceRoleName("owner"))

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.userInfo, samRequestContext))

    Get(s"/api/resources/v2/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/public") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK withClue responsePayloadClue(responseAs[String])
      responseAs[Boolean] should equal(false)
    }
  }

  it should "403 if user cannot read policies" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.delete),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.delete))),
      ResourceRoleName("owner"))

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.userInfo, samRequestContext))

    Get(s"/api/resources/v2/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/public") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden withClue responsePayloadClue(responseAs[String])
    }
  }

  "PUT /api/resources/v2/{resourceType}/{resourceId}/policies/{policyName}/public" should "204 if user has alter_policies and set_public" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies))),
      ResourceRoleName("owner"))

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(samRoutes.resourceService.addSubjectToPolicy(model.FullyQualifiedPolicyId(
      model.FullyQualifiedResourceId(TestSamRoutes.resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName(TestSamRoutes.resourceTypeAdmin.ownerRoleName.value)), samRoutes.userInfo.userId, samRequestContext))

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.userInfo, samRequestContext))

    Put(s"/api/resources/v2/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/public", true) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent withClue responsePayloadClue(responseAs[String])
    }
  }

  it should "204 if user has share_policy:: and set_public::" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.sharePolicy, SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.sharePolicy(AccessPolicyName("owner")), SamResourceActions.readPolicies))),
      ResourceRoleName("owner"))

    val resourceTypeAdmin = ResourceType(
      ResourceTypeName("resource_type_admin"),
      Set(
        SamResourceActionPatterns.alterPolicies,
        SamResourceActionPatterns.readPolicies,
        SamResourceActionPatterns.sharePolicy,
        SamResourceActionPatterns.readPolicy,
        SamResourceActionPatterns.setPublic,
        SamResourceActionPatterns.setPolicyPublic
      ),
      Set(
        ResourceRole(
          ResourceRoleName("owner"),
          Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies, SamResourceActions.setPublicPolicy(AccessPolicyName("owner"))))),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(samRoutes.resourceService.addSubjectToPolicy(model.FullyQualifiedPolicyId(
      model.FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName(resourceTypeAdmin.ownerRoleName.value)), samRoutes.userInfo.userId, samRequestContext))

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.userInfo, samRequestContext))

    Put(s"/api/resources/v2/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/public", true) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent withClue responsePayloadClue(responseAs[String])
    }
  }

  it should "403 if user does not have policy access" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicies))),
      ResourceRoleName("owner"))

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(samRoutes.resourceService.addSubjectToPolicy(model.FullyQualifiedPolicyId(
      model.FullyQualifiedResourceId(TestSamRoutes.resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName(TestSamRoutes.resourceTypeAdmin.ownerRoleName.value)), samRoutes.userInfo.userId, samRequestContext))

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.userInfo, samRequestContext))

    Put(s"/api/resources/v2/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/public", true) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden withClue responsePayloadClue(responseAs[String])
    }
  }

  it should "404 if user does not have set public access" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies))),
      ResourceRoleName("owner"))

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.userInfo, samRequestContext))

    Put(s"/api/resources/v2/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/public", true) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound withClue responsePayloadClue(responseAs[String])
    }
  }

  "GET /api/resources/v2/{resourceType}/{resourceId}/authDomain" should "200 with auth domain if auth domain is set and user has read_auth_domain" in {
    val managedGroupResourceType = initManagedGroupResourceType()

    val authDomain = "authDomain"
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readAuthDomain, SamResourceActionPatterns.use),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readAuthDomain, ManagedGroupService.useAction))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType, managedGroupResourceType.name -> managedGroupResourceType))

    runAndWait(samRoutes.managedGroupService.createManagedGroup(ResourceId(authDomain), defaultUserInfo, samRequestContext = samRequestContext))

    val resourceId = ResourceId("foo")
    val policiesMap = Map(AccessPolicyName("ap") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(SamResourceActions.readAuthDomain, ManagedGroupService.useAction), Set(ResourceRoleName("owner"))))
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, policiesMap, Set(WorkbenchGroupName(authDomain)), None, defaultUserInfo.userId, samRequestContext))

    Get(s"/api/resources/v2/${resourceType.name}/${resourceId.value}/authDomain") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[String]] shouldEqual Set(authDomain)
    }
  }

  it should "200 with an empty set when the user has read_auth_domain but there is no auth domain set" in {
    val managedGroupResourceType = initManagedGroupResourceType()

    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readAuthDomain, SamResourceActionPatterns.use),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readAuthDomain, ManagedGroupService.useAction))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType, managedGroupResourceType.name -> managedGroupResourceType))

    val resourceId = ResourceId("foo")
    val policiesMap = Map(AccessPolicyName("ap") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(SamResourceActions.readAuthDomain, ManagedGroupService.useAction), Set(ResourceRoleName("owner"))))
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, policiesMap, Set.empty, None, defaultUserInfo.userId, samRequestContext))

    Get(s"/api/resources/v2/${resourceType.name}/${resourceId.value}/authDomain") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[String]] shouldEqual Set.empty
    }
  }

  it should "403 when user does not have read_auth_domain" in {
    val managedGroupResourceType = initManagedGroupResourceType()

    val authDomain = "authDomain"
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.use),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ManagedGroupService.useAction))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType, managedGroupResourceType.name -> managedGroupResourceType))

    runAndWait(samRoutes.managedGroupService.createManagedGroup(ResourceId(authDomain), defaultUserInfo, samRequestContext = samRequestContext))

    val resourceId = ResourceId("foo")
    val policiesMap = Map(AccessPolicyName("ap") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ManagedGroupService.useAction), Set(ResourceRoleName("owner"))))
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, policiesMap, Set(WorkbenchGroupName(authDomain)), None, defaultUserInfo.userId, samRequestContext))

    Get(s"/api/resources/v2/${resourceType.name}/${resourceId.value}/authDomain") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when resource or resource type is not found" in {
    val managedGroupResourceType = initManagedGroupResourceType()

    val authDomain = "authDomain"
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readAuthDomain, SamResourceActionPatterns.use),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readAuthDomain, ManagedGroupService.useAction))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType, managedGroupResourceType.name -> managedGroupResourceType))

    runAndWait(samRoutes.managedGroupService.createManagedGroup(ResourceId(authDomain), defaultUserInfo, samRequestContext = samRequestContext))

    val resourceId = ResourceId("foo")
    val policiesMap = Map(AccessPolicyName("ap") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(SamResourceActions.readAuthDomain, ManagedGroupService.useAction), Set(ResourceRoleName("owner"))))
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, policiesMap, Set(WorkbenchGroupName(authDomain)), None, defaultUserInfo.userId, samRequestContext))

    Get(s"/api/resources/v2/fakeResourceTypeName/$resourceId/authDomain") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }

    Get(s"/api/resources/v2/${resourceType.name}/fakeResourceId/authDomain") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 when the user is not a member of any policy on the resource" in {
    val managedGroupResourceType = initManagedGroupResourceType()

    val authDomain = "authDomain"
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readAuthDomain, SamResourceActionPatterns.use),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readAuthDomain, ManagedGroupService.useAction))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType, managedGroupResourceType.name -> managedGroupResourceType))

    runAndWait(samRoutes.managedGroupService.createManagedGroup(ResourceId(authDomain), defaultUserInfo, samRequestContext = samRequestContext))

    val resourceId = ResourceId("foo")
    val policiesMap = Map(AccessPolicyName("ap") -> AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(SamResourceActions.readAuthDomain, ManagedGroupService.useAction), Set(ResourceRoleName("owner"))))
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, policiesMap, Set(WorkbenchGroupName(authDomain)), None, defaultUserInfo.userId, samRequestContext))

    Get(s"/api/resources/v2/${resourceType.name}/${resourceId.value}/authDomain") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[String]] shouldEqual Set(authDomain)
    }

    val otherUserSamRoutes = TestSamRoutes(Map(resourceType.name -> resourceType), UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user2"), WorkbenchEmail("user2@example.com"), 0))

    Get(s"/api/resources/v2/${resourceType.name}/${resourceId.value}/authDomain") ~> otherUserSamRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  private def initManagedGroupResourceType(): ResourceType = {
    val accessPolicyNames = Set(ManagedGroupService.adminPolicyName, ManagedGroupService.memberPolicyName, ManagedGroupService.adminNotifierPolicyName)
    val policyActions: Set[ResourceAction] = accessPolicyNames.flatMap(policyName => Set(SamResourceActions.sharePolicy(policyName), SamResourceActions.readPolicy(policyName)))
    val resourceActions = Set(ResourceAction("delete"), ResourceAction("notify_admins"), ResourceAction("set_access_instructions"), ManagedGroupService.useAction) union policyActions
    val resourceActionPatterns = resourceActions.map(action => ResourceActionPattern(action.value, "", false))
    val defaultOwnerRole = ResourceRole(ManagedGroupService.adminRoleName, resourceActions)
    val defaultMemberRole = ResourceRole(ManagedGroupService.memberRoleName, Set.empty)
    val defaultAdminNotifierRole = ResourceRole(ManagedGroupService.adminNotifierRoleName, Set(ResourceAction("notify_admins")))
    val defaultRoles = Set(defaultOwnerRole, defaultMemberRole, defaultAdminNotifierRole)

    ResourceType(ManagedGroupService.managedGroupTypeName, resourceActionPatterns, defaultRoles, ManagedGroupService.adminRoleName)
  }

  "GET /api/resources/v2/{resourceTypeName}/{resourceId}/allUsers" should "200 with all users list when user has read_policies action" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.userInfo, samRequestContext))

    val user = samRoutes.directoryDAO.loadUser(samRoutes.userInfo.userId, samRequestContext).unsafeRunSync().get
    val userIdInfo = UserIdInfo(user.id, user.email, user.googleSubjectId)

    Get(s"/api/resources/v2/${resourceType.name}/${resourceId.value}/allUsers") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[UserIdInfo]].map(_.userSubjectId) shouldEqual Set(userIdInfo.userSubjectId)
    }
  }

  it should "403 when user does not have read_policies action" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set.empty,
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readAuthDomain))), // any action except read_policies
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.userInfo, samRequestContext))

    Get(s"/api/resources/v2/${resourceType.name}/${resourceId.value}/allUsers") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when resource or resourceType does not exist" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.userInfo, samRequestContext))

    Get(s"/api/resources/v2/fakeResourceTypeName/${resourceId.value}/allUsers") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }

    Get(s"/api/resources/v2/${resourceType.name}/fakeResourceId/allUsers") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 when user is not in any of the policies on the resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.userInfo, samRequestContext))

    val user = samRoutes.directoryDAO.loadUser(samRoutes.userInfo.userId, samRequestContext).unsafeRunSync().get
    val userIdInfo = UserIdInfo(user.id, user.email, user.googleSubjectId)

    Get(s"/api/resources/v2/${resourceType.name}/${resourceId.value}/allUsers") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[UserIdInfo]].map(_.userSubjectId) shouldEqual Set(userIdInfo.userSubjectId)
    }

    val otherUserSamRoutes = TestSamRoutes(Map(resourceType.name -> resourceType), UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user2"), WorkbenchEmail("user2@example.com"), 0))

    Get(s"/api/resources/v2/${resourceType.name}/${resourceId.value}/allUsers") ~> otherUserSamRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  private def mockPermissionsForResource(samRoutes: SamRoutes,
                                         resource: FullyQualifiedResourceId,
                                         actionsOnResource: Set[ResourceAction]): Unit = {

    val actionAllowed = new ArgumentMatcher[Iterable[ResourceAction]] {
      override def matches(argument: Iterable[ResourceAction]): Boolean = actionsOnResource.intersect(argument.toSet).nonEmpty
    }
    val actionNotAllowed = new ArgumentMatcher[Iterable[ResourceAction]] {
      override def matches(argument: Iterable[ResourceAction]): Boolean = actionsOnResource.intersect(argument.toSet).isEmpty
    }

    when(samRoutes.policyEvaluatorService.hasPermissionOneOf(mockitoEq(resource), argThat(actionAllowed), mockitoEq(defaultUserInfo.userId), any[SamRequestContext])).
      thenReturn(IO.pure(true))

    when(samRoutes.policyEvaluatorService.hasPermissionOneOf(mockitoEq(resource), argThat(actionNotAllowed), mockitoEq(defaultUserInfo.userId), any[SamRequestContext])).
      thenReturn(IO.pure(false))

    when(samRoutes.policyEvaluatorService.listUserResourceActions(mockitoEq(resource), mockitoEq(defaultUserInfo.userId), any[SamRequestContext])).
      thenReturn(IO.pure(actionsOnResource))
  }

  // mock out a bunch of calls in ResourceService and PolicyEvaluatorService to reduce bloat in /parent tests
  private def setupParentRoutes(samRoutes: SamRoutes,
                                childResource: FullyQualifiedResourceId,
                                currentParentOpt: Option[FullyQualifiedResourceId] = None,
                                newParentOpt: Option[FullyQualifiedResourceId] = None,
                                actionsOnChild: Set[ResourceAction],
                                actionsOnCurrentParent: Set[ResourceAction] = Set.empty,
                                actionsOnNewParent: Set[ResourceAction] = Set.empty): Unit = {
    // mock responses for child resource
    mockPermissionsForResource(samRoutes, childResource, actionsOnResource = actionsOnChild)

    // mock responses for current parent resource
    currentParentOpt match {
      case Some(currentParent) =>
        when(samRoutes.resourceService.getResourceParent(mockitoEq(childResource), any[SamRequestContext]))
          .thenReturn(IO(Option(currentParent)))
        when(samRoutes.resourceService.deleteResourceParent(mockitoEq(childResource), any[SamRequestContext]))
          .thenReturn(IO.pure(true))
        mockPermissionsForResource(samRoutes, currentParent,
          actionsOnResource = actionsOnCurrentParent)
      case None =>
        when(samRoutes.resourceService.getResourceParent(mockitoEq(childResource), any[SamRequestContext]))
          .thenReturn(IO(None))
        when(samRoutes.resourceService.deleteResourceParent(mockitoEq(childResource), any[SamRequestContext]))
          .thenReturn(IO.pure(false))
    }

    // mock responses for new parent resource
    newParentOpt.map { newParent =>
      when(samRoutes.resourceService.setResourceParent(mockitoEq(childResource), mockitoEq(newParent), any[SamRequestContext]))
        .thenReturn(IO.unit)
      mockPermissionsForResource(samRoutes, newParent, actionsOnResource = actionsOnNewParent)
    }

    if (actionsOnChild.contains(SamResourceActions.delete) && actionsOnCurrentParent.contains(SamResourceActions.removeChild)) {
      when(samRoutes.resourceService.deleteResource(mockitoEq(childResource), any[SamRequestContext])).thenReturn(Future.unit)
    }
  }

  "GET /api/resources/v2/{resourceTypeName}/{resourceId}/parent" should "200 if user has get_parent on resource and resource has parent" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val fullyQualifiedParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("parent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(fullyQualifiedParentResource),
      actionsOnChild = Set(SamResourceActions.getParent))

    Get(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[FullyQualifiedResourceId] shouldEqual fullyQualifiedParentResource
    }
  }

  it should "403 if user is missing get_parent on resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val fullyQualifiedParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("parent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource, currentParentOpt = Option(fullyQualifiedParentResource),
      actionsOnChild = Set(SamResourceActions.readPolicies))

    Get(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if resource has no parent" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource, None, actionsOnChild = Set(SamResourceActions.getParent))

    Get(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 if user doesn't have access to resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource, actionsOnChild = Set.empty)

    Get(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PUT /api/resources/v2/{resourceTypeName}/{resourceId}/parent" should "204 on success when there is not a parent already set" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val fullyQualifiedParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("parent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource, newParentOpt = Option(fullyQualifiedParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      actionsOnNewParent = Set(SamResourceActions.addChild))

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", fullyQualifiedParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "204 on success when there is a parent already set" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      newParentOpt = Option(newParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild),
      actionsOnNewParent = Set(SamResourceActions.addChild))

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "403 if user is missing set_parent on child resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      newParentOpt = Option(newParentResource),
      actionsOnChild = Set(SamResourceActions.readPolicies),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild),
      actionsOnNewParent = Set(SamResourceActions.addChild))

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "403 if user is missing add_child on new parent resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      newParentOpt = Option(newParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild),
      actionsOnNewParent = Set(SamResourceActions.readPolicies))

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "403 if user is missing remove_child on existing parent resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      newParentOpt = Option(newParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      actionsOnCurrentParent = Set(SamResourceActions.readPolicies),
      actionsOnNewParent = Set(SamResourceActions.addChild))

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if user doesn't have access to child resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      newParentOpt = Option(newParentResource),
      actionsOnChild = Set.empty,
      actionsOnCurrentParent = Set(SamResourceActions.removeChild),
      actionsOnNewParent = Set(SamResourceActions.addChild))

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "403 if user doesn't have access to new parent resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      newParentOpt = Option(newParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild),
      actionsOnNewParent = Set(SamResourceActions.readPolicies))

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "403 if the new parent resource does not exist" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val nonexistentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("nonexistentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = None,
      newParentOpt = Option(nonexistentParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      actionsOnNewParent = Set(SamResourceActions.readPolicies)
    )

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", nonexistentParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "403 if user doesn't have access to existing parent resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val newParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("newParent"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      newParentOpt = Option(newParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      actionsOnCurrentParent = Set.empty,
      actionsOnNewParent = Set(SamResourceActions.addChild))

    Put(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent", newParentResource) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "DELETE /api/resources/v2/{resourceTypeName}/{resourceId}/parent" should "204 on success" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild))

    Delete(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "403 if user is missing set_parent on child resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      actionsOnChild = Set(SamResourceActions.readPolicies),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild))

    Delete(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "403 if user is missing remove_child on parent resource if it exists" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      actionsOnCurrentParent = Set(SamResourceActions.readPolicies))

    Delete(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if resource has no parent" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      actionsOnChild = Set(SamResourceActions.setParent))

    Delete(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 if user doesn't have access to child resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      actionsOnChild = Set.empty,
      actionsOnCurrentParent = Set(SamResourceActions.removeChild))

    Delete(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "403 if user doesn't have access to existing parent resource" in {
    val fullyQualifiedChildResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes()

    setupParentRoutes(samRoutes, fullyQualifiedChildResource,
      currentParentOpt = Option(currentParentResource),
      actionsOnChild = Set(SamResourceActions.setParent),
      actionsOnCurrentParent = Set.empty)

    Delete(s"/api/resources/v2/${defaultResourceType.name}/${fullyQualifiedChildResource.resourceId.value}/parent") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "GET /api/resources/v2/{resourceTypeName}/{resourceId}/children" should "200 with list of children FullyQualifiedResourceIds on success" in {
    val child1 = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child1"))
    val child2 = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child2"))
    val parent = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("parent"))

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, parent, actionsOnResource = Set(SamResourceActions.listChildren))

    when(samRoutes.resourceService.listResourceChildren(mockitoEq(parent), any[SamRequestContext]))
      .thenReturn(IO(Set(child1, child2)))

    Get(s"/api/resources/v2/${defaultResourceType.name}/${parent.resourceId.value}/children") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[FullyQualifiedResourceId]] shouldEqual Set(child1, child2)
    }
  }

  it should "403 if user is missing list_children on the parent resource" in {
    val parent = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("parent"))
    val otherPolicy = AccessPolicyWithoutMembers(FullyQualifiedPolicyId(parent, AccessPolicyName("not_owner")), WorkbenchEmail(""), Set.empty, Set.empty, false)

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, parent, actionsOnResource = Set(SamResourceActions.readPolicies))

    Get(s"/api/resources/v2/${defaultResourceType.name}/${parent.resourceId.value}/children") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if user doesn't have access to parent resource" in {
    val parent = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("parent"))

    val samRoutes = createSamRoutes()

    mockPermissionsForResource(samRoutes, parent,
      actionsOnResource = Set.empty)

    Get(s"/api/resources/v2/${defaultResourceType.name}/${parent.resourceId.value}/children") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "DELETE /api/resources/v2/{resourceTypeName}/{resourceId}/policies/{policyName}" should "204 on success" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyToDelete = FullyQualifiedPolicyId(resource, AccessPolicyName("policyToDelete"))

    val samRoutes = createSamRoutes()

    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.alterPolicies, SamResourceActions.deletePolicy(policyToDelete.accessPolicyName)))
    when(samRoutes.resourceService.deletePolicy(mockitoEq(policyToDelete), any[SamRequestContext]))
      .thenReturn(IO.unit)

    Delete(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyToDelete.accessPolicyName}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "403 if user is missing both alter_policies and delete_policy on the resource" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyToDelete = FullyQualifiedPolicyId(resource, AccessPolicyName("policyToDelete"))
    val otherPolicy = AccessPolicyWithoutMembers(FullyQualifiedPolicyId(resource, AccessPolicyName("not_owner")), WorkbenchEmail(""), Set.empty, Set.empty, false)

    val samRoutes = createSamRoutes()

    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.readPolicies))

    Delete(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyToDelete.accessPolicyName}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if user doesn't have access to the resource" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyToDelete = FullyQualifiedPolicyId(resource, AccessPolicyName("policyToDelete"))

    val samRoutes = createSamRoutes()

    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set.empty)

    Delete(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyToDelete.accessPolicyName}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "DELETE /api/resources/v2/{resourceTypeName}/{resourceId}" should "204 on a child resource if the user has remove_child on the parent resource" in {
    val childResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes(Map(defaultResourceType.name -> defaultResourceType))

    setupParentRoutes(samRoutes, childResource,
      currentParentOpt = Option(currentParentResource),
      actionsOnChild = Set(SamResourceActions.setParent, SamResourceActions.delete),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild))

    //Delete the resource
    Delete(s"/api/resources/v2/${defaultResourceType.name}/${childResource.resourceId.value}") ~> samRoutes.route ~> check {
      withClue(responseAs[String]) {
        status shouldEqual StatusCodes.NoContent
      }
    }
  }

  it should "403 if user is missing remove_child on parent resource if it exists" in {
    val childResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val currentParentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("currentParent"))
    val samRoutes = createSamRoutes(Map(defaultResourceType.name -> defaultResourceType))

    setupParentRoutes(samRoutes, childResource,
      currentParentOpt = Option(currentParentResource),
      actionsOnChild = Set(SamResourceActions.setParent, SamResourceActions.delete),
      actionsOnCurrentParent = Set(SamResourceActions.readPolicies))

    Delete(s"/api/resources/v2/${defaultResourceType.name}/${childResource.resourceId.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "GET /api/resources/v2/{resourceType}/{resourceId}/policies/{policyName}" should "200 on existing policy of a resource with read_policies" in {
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set.empty, Set.empty, None)
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")

    val samRoutes = createSamRoutes()

    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.readPolicies))

    // mock response to load policy
    when(samRoutes.resourceService.loadResourcePolicy(mockitoEq(FullyQualifiedPolicyId(resource, policyName)), any[SamRequestContext]))
      .thenReturn(IO(Option(members)))

    Get(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[AccessPolicyMembership] shouldEqual members
    }
  }

  it should "200 on existing policy if user can read just that policy" in {
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set.empty, Set.empty, None)
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")

    val samRoutes = createSamRoutes()

    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.readPolicy(policyName)))

    // mock response to load policy
    when(samRoutes.resourceService.loadResourcePolicy(mockitoEq(FullyQualifiedPolicyId(resource, policyName)), any[SamRequestContext]))
      .thenReturn(IO(Option(members)))

    Get(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[AccessPolicyMembership] shouldEqual members
    }
  }

  it should "403 on existing policy of a resource without read policies" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")

    val samRoutes = createSamRoutes()

    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.delete))

    Get(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "400 when attempting to delete a resource with children" in {
    val childResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("child"))
    val parentResource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("parent"))
    val samRoutes = createSamRoutes(Map(defaultResourceType.name -> defaultResourceType))

    setupParentRoutes(samRoutes, childResource,
      currentParentOpt = Option(parentResource),
      actionsOnChild = Set(SamResourceActions.setParent, SamResourceActions.delete),
      actionsOnCurrentParent = Set(SamResourceActions.removeChild))

    //Throw 400 exception when delete is called
    when(samRoutes.resourceService.deleteResource(mockitoEq(childResource), any[SamRequestContext]))
      .thenThrow(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "Cannot delete a resource with children. Delete the children first then try again.")))

    //Delete the resource
    Delete(s"/api/resources/v2/${defaultResourceType.name}/${childResource.resourceId.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "404 on non existing policy of a resource" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")

    val samRoutes = createSamRoutes()

    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set.empty)

    Get(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PUT /api/resources/v2/{resourceType}/{resourceId}/policies/{policyName}" should "201 on a new policy being created for a resource" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute")), Set.empty, None)
    val policy = AccessPolicy(FullyQualifiedPolicyId(resource, policyName), Set(defaultUserInfo.userId), WorkbenchEmail("policy@example.com"), members.roles, members.actions, members.getDescendantPermissions, false)

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.alterPolicies))

    when(samRoutes.resourceService.overwritePolicy(any[ResourceType], mockitoEq(policyName), mockitoEq(resource), mockitoEq(members), any[SamRequestContext]))
      .thenReturn(IO(policy))

    Put(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName}", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }
  }

  it should "201 on a policy being updated" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute")), Set.empty, None)
    val policy = AccessPolicy(FullyQualifiedPolicyId(resource, policyName), Set(defaultUserInfo.userId), WorkbenchEmail("policy@example.com"), members.roles, members.actions, members.getDescendantPermissions, false)

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.alterPolicies))

    when(samRoutes.resourceService.overwritePolicy(any[ResourceType], mockitoEq(policyName), mockitoEq(resource), mockitoEq(members), any[SamRequestContext]))
      .thenReturn(IO(policy))

    Put(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName}", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    // update existing policy
    val members2 = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute"), ResourceAction("new_action")), Set.empty, None)
    val policy2 = AccessPolicy(FullyQualifiedPolicyId(resource, policyName), Set(defaultUserInfo.userId), WorkbenchEmail("policy@example.com"), members2.roles, members2.actions, members2.getDescendantPermissions, false)
    when(samRoutes.resourceService.overwritePolicy(any[ResourceType], mockitoEq(policyName), mockitoEq(resource), mockitoEq(members2), any[SamRequestContext]))
      .thenReturn(IO(policy2))

    Put(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName}", members2) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }
  }

  it should "400 when creating an invalid policy" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute")), Set.empty, None)

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.alterPolicies))

    when(samRoutes.resourceService.overwritePolicy(any[ResourceType], mockitoEq(policyName), mockitoEq(resource), mockitoEq(members), any[SamRequestContext]))
      .thenReturn(IO.raiseError(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, "You have specified an invalid policy"))))

    Put(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName}", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "403 when creating a policy on a resource when the user doesn't have alter_policies permission (but can see the resource)" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute")), Set.empty, None)

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.readPolicies))

    Put(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName}", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when creating a policy on a resource that the user doesnt have permission to see" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute")), Set.empty, None)

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set.empty)

    Put(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies/${policyName}", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/resources/v2/{resourceType}/{resourceId}/policies" should "200 when listing policies for a resource and user has read_policies permission" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute")), Set.empty, None)
    val response = AccessPolicyResponseEntry(policyName, members, WorkbenchEmail("policy@example.com"))

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.readPolicies))

    when(samRoutes.resourceService.listResourcePolicies(mockitoEq(resource), any[SamRequestContext]))
      .thenReturn(IO(LazyList(response)))

    Get(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  it should "403 when listing policies for a resource and user lacks read_policies permission (but can see the resource)" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute")), Set.empty, None)
    val response = AccessPolicyResponseEntry(policyName, members, WorkbenchEmail("policy@example.com"))

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set(SamResourceActions.delete))

    when(samRoutes.resourceService.listResourcePolicies(mockitoEq(resource), any[SamRequestContext]))
      .thenReturn(IO(LazyList(response)))

    Get(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when listing policies for a resource when user can't see the resource" in {
    val resource = FullyQualifiedResourceId(defaultResourceType.name, ResourceId("resource"))
    val policyName = AccessPolicyName("policy")
    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(ResourceAction("can_compute")), Set.empty, None)
    val response = AccessPolicyResponseEntry(policyName, members, WorkbenchEmail("policy@example.com"))

    val samRoutes = createSamRoutes()
    mockPermissionsForResource(samRoutes, resource,
      actionsOnResource = Set.empty)

    when(samRoutes.resourceService.listResourcePolicies(mockitoEq(resource), any[SamRequestContext]))
      .thenReturn(IO(LazyList(response)))

    Get(s"/api/resources/v2/${resource.resourceTypeName}/${resource.resourceId}/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }
}
