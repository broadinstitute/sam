package org.broadinstitute.dsde.workbench.sam
package api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport._
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes.SamResourceActionPatterns
import org.broadinstitute.dsde.workbench.sam.dataAccess.{MockAccessPolicyDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model.RootPrimitiveJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api._
import org.broadinstitute.dsde.workbench.sam.service._
import org.scalatest.AppendedClues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spray.json.DefaultJsonProtocol._
import spray.json.{JsBoolean, JsValue}

/** Created by dvoet on 6/7/17.
  */
@deprecated("this allows testing of deprecated functions, remove as part of CA-1783", "")
class ResourceRoutesV1Spec extends AnyFlatSpec with Matchers with ScalatestRouteTest with TestSupport with AppendedClues {

  val defaultUserInfo = TestSamRoutes.defaultUserInfo

  private val managedGroupResourceType =
    configResourceTypes.getOrElse(ResourceTypeName("managed-group"), throw new Error("Failed to load managed-group resource type from reference.conf"))

  private val defaultTestUser = Generator.genWorkbenchUserGoogle.sample.get

  "GET /api/resources/v1/{resourceType}/{resourceId}/actions/{action}" should "404 for unknown resource type" in {
    val samRoutes = TestSamRoutes(Map.empty)

    Get("/api/resources/v1/foo/bar/action") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
      responseAs[ErrorReport].message shouldEqual "resource type foo not found"
    }
  }

  "GET /api/resource/{resourceType}/{resourceId}/action/{action}/userEmail/{userEmail}" should "200 if caller have read_policies permission" in {
    // Create a user called userWithEmail and has can_compute on the resource.
    val userWithEmail = Generator.genWorkbenchUserGoogle.sample.get
    val members = AccessPolicyMembershipResponse(Set(userWithEmail.email), Set(ResourceAction("can_compute")), Set.empty)
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
    val members = AccessPolicyMembershipResponse(Set(userWithEmail.email), Set(ResourceAction("can_compute")), Set.empty)
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
    val members = AccessPolicyMembershipResponse(Set(userWithEmail.email), Set(ResourceAction("read_policies")), Set.empty)

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
    val members = AccessPolicyMembershipResponse(Set(userWithEmail.email), Set(ResourceAction("read_policies")), Set.empty)

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

  "GET /api/config/v1/resourceTypes" should "200 when listing all resource types" in {
    val samRoutes = TestSamRoutes(Map.empty)

    Get("/api/config/v1/resourceTypes") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  "POST /api/resources/v1/{resourceType}" should "204 create resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(ResourceActionPattern("run", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val createResourceRequest = CreateResourceRequest(
      ResourceId("foo"),
      Map(AccessPolicyName("goober") -> AccessPolicyMembershipRequest(Set(defaultUserInfo.email), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))),
      Set.empty
    )
    Post(s"/api/resources/v1/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/resources/v1/${resourceType.name}/foo/action/run") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[JsValue] shouldEqual JsBoolean(true)
    }
  }

  it should "400 for resourceTypeAdmin" in {
    val samRoutes = TestSamRoutes(Map.empty)

    val createResourceRequest = CreateResourceRequest(
      ResourceId("foo"),
      Map(AccessPolicyName("goober") -> AccessPolicyMembershipRequest(Set(defaultUserInfo.email), Set(ResourceAction("run")), Set.empty)),
      Set.empty
    )
    Post(s"/api/resources/v1/${SamResourceTypes.resourceTypeAdminName}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  "POST /api/resources/v1/{resourceType} with createResource = true" should "201 create resource with content" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(ResourceActionPattern("run", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val createResourceRequest = CreateResourceRequest(
      ResourceId("foo"),
      Map(AccessPolicyName("goober") -> AccessPolicyMembershipRequest(Set(defaultUserInfo.email), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))),
      Set.empty,
      Some(true)
    )
    Post(s"/api/resources/v1/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      val r = responseAs[CreateResourceResponse]

      r.resourceId shouldEqual createResourceRequest.resourceId
      r.authDomain shouldEqual createResourceRequest.authDomain
      r.resourceTypeName shouldEqual resourceType.name

      val returnedNames = r.accessPolicies.map(x => x.id.accessPolicyName)
      createResourceRequest.policies.keys.foreach { k =>
        returnedNames.contains(k) shouldEqual true
      }
    }
  }

  "POST /api/resources/v1/{resourceType} with createResource = false" should "204 create resource with content" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(ResourceActionPattern("run", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val createResourceRequest = CreateResourceRequest(
      ResourceId("foo"),
      Map(AccessPolicyName("goober") -> AccessPolicyMembershipRequest(Set(defaultUserInfo.email), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))),
      Set.empty,
      Some(false)
    )
    Post(s"/api/resources/v1/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
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
      Map(AccessPolicyName("goober") -> AccessPolicyMembershipRequest(Set(defaultUserInfo.email), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))),
      authDomain
    )
    Post(s"/api/resources/v1/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/resources/v1/${resourceType.name}/foo/action/run") ~> samRoutes.route ~> check {
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
      Map(AccessPolicyName("goober") -> AccessPolicyMembershipRequest(Set(defaultUserInfo.email), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))),
      Set.empty
    )
    Post(s"/api/resources/v1/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
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
    Post(s"/api/resources/v1/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
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
      Map(AccessPolicyName("goober") -> AccessPolicyMembershipRequest(Set(defaultUserInfo.email), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))),
      authDomain
    )
    Post(s"/api/resources/v1/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
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
      Map(AccessPolicyName("goober") -> AccessPolicyMembershipRequest(Set(defaultUserInfo.email), Set(ResourceAction("run")), Set(resourceType.ownerRoleName))),
      authDomain
    )
    Post(s"/api/resources/v1/${resourceType.name}", createResourceRequest) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  "POST /api/resources/v1/{resourceType}/{resourceId}" should "204 create resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(ResourceActionPattern("run", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/resources/v1/${resourceType.name}/foo/action/run") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[JsValue] shouldEqual JsBoolean(true)
    }

  }

  "GET /api/resources/v1/{resourceType}/{resourceId}/roles" should "200 on list resource roles" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(ResourceActionPattern("run", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/resources/v1/${resourceType.name}/foo/roles") ~> samRoutes.route ~> check {
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

    Get(s"/api/resources/v1/doesntexist/foo/roles") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/resources/v1/{resourceType}/{resourceId}/actions" should "200 on list resource actions" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(ResourceActionPattern("run", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/resources/v1/${resourceType.name}/foo/actions") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[String]]
    }
  }

  it should "404 on list resource actions when resource type doesnt exist" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(ResourceActionPattern("run", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Get(s"/api/resources/v1/doesntexist/foo/actions") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/resources/v1/{resourceType}/{resourceId}/policies/{policyName}" should "200 on existing policy of a resource with read_policies" in {
    val members = AccessPolicyMembershipResponse(Set(defaultUserInfo.email), Set.empty, Set.empty)
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

    Get(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/policies/${policyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[AccessPolicyMembershipResponse] shouldEqual members
    }
  }

  private def responsePayloadClue(str: String): String = s" -> Here is the response payload: $str"

  private def createUserResourcePolicy(
      members: AccessPolicyMembershipResponse,
      resourceType: ResourceType,
      samRoutes: TestSamRoutes,
      resourceId: ResourceId,
      policyName: AccessPolicyName
  ): Unit = {
    findOrCreateUser(samRoutes.user, samRoutes.userService)

    Post(s"/api/resources/v1/${resourceType.name}/${resourceId.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent withClue responsePayloadClue(responseAs[String])
    }

    Put(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/policies/${policyName.value}", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created withClue responsePayloadClue(responseAs[String])
    }
  }

  private def findOrCreateUser(user: SamUser, userService: UserService): UserStatus =
    runAndWait(userService.getUserStatus(user.id, samRequestContext = samRequestContext)) match {
      case Some(userStatus) => userStatus
      case None => runAndWait(userService.createUser(user, samRequestContext))
    }

  it should "200 on existing policy of a resource with read_policy" in {
    val members = AccessPolicyMembershipResponse(Set(defaultUserInfo.email), Set.empty, Set.empty)
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

    Get(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/policies/${policyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[AccessPolicyMembershipResponse] shouldEqual members
    }
  }

  it should "404 on non existing policy of a resource" in {
    val members = AccessPolicyMembershipResponse(Set(defaultUserInfo.email), Set.empty, Set.empty)
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

    Get(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/policies/${policyName.value}_dne") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "403 on existing policy of a resource without read policies" in {
    val members = AccessPolicyMembershipResponse(Set(defaultUserInfo.email), Set.empty, Set.empty)
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

    Get(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/policies/${policyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  "PUT /api/resources/v1/{resourceType}/{resourceId}/policies/{policyName}" should "201 on a new policy being created for a resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    val members = AccessPolicyMembershipResponse(Set(defaultTestUser.email), Set(ResourceAction("can_compute")), Set.empty)

    Put(s"/api/resources/v1/${resourceType.name}/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
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

    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    val members = AccessPolicyMembershipResponse(Set(defaultTestUser.email), Set(ResourceAction("can_compute")), Set.empty)

    Put(s"/api/resources/v1/${resourceType.name}/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    val members2 = AccessPolicyMembershipResponse(Set(defaultTestUser.email), Set(ResourceAction("can_compute")), Set(ResourceRoleName("owner")))

    Put(s"/api/resources/v1/${resourceType.name}/foo/policies/canCompute", members2) ~> samRoutes.route ~> check {
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

    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    val members = AccessPolicyMembershipResponse(Set(defaultTestUser.email), Set(ResourceAction("can_compute")), Set.empty)

    Put(s"/api/resources/v1/${resourceType.name}/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    val members2 = Set(defaultTestUser.email)

    Put(s"/api/resources/v1/${resourceType.name}/foo/policies/canCompute/memberEmails", members2) ~> samRoutes.route ~> check {
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

    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    val fakeActions = Set(ResourceAction("fake_action1"), ResourceAction("other_fake_action"))
    val members = AccessPolicyMembershipResponse(Set(defaultTestUser.email), fakeActions, Set.empty)

    Put(s"/api/resources/v1/${resourceType.name}/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
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

    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    val fakeRoles = Set(ResourceRoleName("fakerole"), ResourceRoleName("otherfakerole"))
    val members = AccessPolicyMembershipResponse(Set(defaultTestUser.email), Set(ResourceAction("can_compute")), fakeRoles)

    Put(s"/api/resources/v1/${resourceType.name}/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
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

    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    val badEmail = WorkbenchEmail("null@bar.baz")
    val nonExistingMembers = AccessPolicyMembershipResponse(Set(badEmail), Set(ResourceAction("can_compute")), Set(ResourceRoleName("owner")))

    Put(s"/api/resources/v1/${resourceType.name}/foo/policies/canCompute", nonExistingMembers) ~> samRoutes.route ~> check {
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
    val members = AccessPolicyMembershipResponse(Set(WorkbenchEmail("me@me.me")), Set(ResourceAction("can_compute")), Set.empty)

    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Put(s"/api/resources/v1/${resourceType.name}/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when creating a policy on a resource type that doesnt exist" in {
    val samRoutes = TestSamRoutes(Map.empty)
    val members = AccessPolicyMembershipResponse(Set(WorkbenchEmail("foo@bar.baz")), Set(ResourceAction("can_compute")), Set.empty)

    // Create a resource of a type that doesn't exist
    Put(s"/api/resources/v1/fakeresourcetype/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
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
    val policyDao = new MockAccessPolicyDAO(resourceType, directoryDAO)
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType), policyAccessDAO = Some(policyDao), maybeDirectoryDAO = Some(directoryDAO))

    policyDao.createResource(Resource(resourceType.name, ResourceId("foo"), Set.empty), samRequestContext).unsafeRunSync()

    val otherUserSamRoutes = TestSamRoutes(
      Map(resourceType.name -> resourceType),
      Generator.genWorkbenchUserGoogle.sample.get,
      policyAccessDAO = Some(policyDao),
      maybeDirectoryDAO = Some(directoryDAO)
    )
    val members = AccessPolicyMembershipResponse(Set(WorkbenchEmail("foo@bar.baz")), Set(ResourceAction("can_compute")), Set.empty)

    // Create a resource
    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    // As a different user who isn't on any policy, try to overwrite a policy
    Put(s"/api/resources/v1/${resourceType.name}/foo/policies/canCompute", members) ~> otherUserSamRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/resources/v1/{resourceType}/{resourceId}/policies" should "200 when listing policies for a resource and user has read_policies permission" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    // Create a resource
    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    // Read the policies
    Get(s"/api/resources/v1/${resourceType.name}/foo/policies") ~> samRoutes.route ~> check {
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
    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    // Try to read the policies
    Get(s"/api/resources/v1/${resourceType.name}/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when listing policies for a resource type that doesnt exist" in {
    val samRoutes = TestSamRoutes(Map.empty)

    // List policies for a bogus resource type
    Get(s"/api/resources/v1/fakeresourcetype/foo/policies") ~> samRoutes.route ~> check {
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
    val policyDao = new MockAccessPolicyDAO(resourceType, directoryDAO)
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType), policyAccessDAO = Some(policyDao), maybeDirectoryDAO = Some(directoryDAO))

    policyDao.createResource(Resource(resourceType.name, ResourceId("foo"), Set.empty), samRequestContext).unsafeRunSync()

    val otherUserSamRoutes = TestSamRoutes(
      Map(resourceType.name -> resourceType),
      Generator.genWorkbenchUserGoogle.sample.get,
      policyAccessDAO = Some(policyDao),
      maybeDirectoryDAO = Some(directoryDAO)
    )

    // Create the resource
    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    // As a different user, try to read the policies
    Get(s"/api/resources/v1/${resourceType.name}/foo/policies") ~> otherUserSamRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "DELETE /api/resources/v1/{resourceType}/{resourceId}" should "204 when deleting a resource and the user has permission to do so" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies, SamResourceActionPatterns.delete),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.delete, SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    // Create the resource
    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    // Read the policies to make sure the resource exists)
    Get(s"/api/resources/v1/${resourceType.name}/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    // Delete the resource
    Delete(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
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
    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    // Read the policies to make sure the resource exists)
    Get(s"/api/resources/v1/${resourceType.name}/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    // Delete the resource
    Delete(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when deleting a resource of a type that doesn't exist" in {
    val samRoutes = TestSamRoutes(Map.empty)

    // Delete the resource
    Delete(s"/api/resources/v1/INVALID_RESOURCE_TYPE/foo") ~> samRoutes.route ~> check {
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
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    samRoutes.resourceService.createResourceType(resourceType, samRequestContext).unsafeRunSync()
    val user = Generator.genWorkbenchUserGoogle.sample.get
    runAndWait(samRoutes.userService.createUser(user, samRequestContext))
    runAndWait(samRoutes.resourceService.createResource(resourceType, ResourceId("foo"), user, samRequestContext))

    // Verify resource exists by checking for conflict on recreate
    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }

    // Delete the resource
    Delete(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/resources/v1/{resourceType}" should "200" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readPolicies, ResourceActionPattern("can_compute", "", false)),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    // Create a resource
    Post(s"/api/resources/v1/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    // Read the policies
    Get(s"/api/resources/v1/${resourceType.name}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[List[UserPolicyResponse]].size should equal(1)
    }
  }

  it should "list public policies" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(
      samRoutes.resourceService.addSubjectToPolicy(
        model.FullyQualifiedPolicyId(
          model.FullyQualifiedResourceId(TestSamRoutes.resourceTypeAdmin.name, ResourceId(resourceType.name.value)),
          AccessPolicyName("owner")
        ),
        samRoutes.user.id,
        samRequestContext
      )
    )

    val resourceId = ResourceId("foo")
    val policyName = AccessPolicyName("bar")
    val members = AccessPolicyMembershipResponse(Set.empty, Set.empty, Set.empty)
    createUserResourcePolicy(members, resourceType, samRoutes, resourceId, policyName)
    samRoutes.resourceService
      .setPublic(model.FullyQualifiedPolicyId(model.FullyQualifiedResourceId(resourceType.name, resourceId), policyName), true, samRequestContext)
      .unsafeRunSync()

    // Read the policies
    Get(s"/api/resources/v1/${resourceType.name}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[List[UserPolicyResponse]].size should equal(2) withClue responsePayloadClue(responseAs[String])
    }
  }

  "PUT /api/resources/v1/{resourceType}/{resourceId}/policies/{policyName}/memberEmails/{email}" should "204 adding a member" in {
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

    Put(
      s"/api/resources/v1/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}"
    ) ~> samRoutes.route ~> check {
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

    Put(
      s"/api/resources/v1/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}"
    ) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
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

    Put(
      s"/api/resources/v1/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}"
    ) ~> samRoutes.route ~> check {
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

    Put(
      s"/api/resources/v1/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}"
    ) ~> samRoutes.route ~> check {
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

    Put(s"/api/resources/v1/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${testUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "DELETE /api/resources/v1/{resourceType}/{resourceId}/policies/{policyName}/memberEmails/{email}" should "204 deleting a member" in {
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

    Delete(
      s"/api/resources/v1/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}"
    ) ~> samRoutes.route ~> check {
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

    Delete(
      s"/api/resources/v1/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}"
    ) ~> samRoutes.route ~> check {
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

    Delete(
      s"/api/resources/v1/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}"
    ) ~> samRoutes.route ~> check {
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

    Delete(
      s"/api/resources/v1/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${defaultTestUser.email}"
    ) ~> samRoutes.route ~> check {
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

    Delete(s"/api/resources/v1/${resourceType.name}/foo/policies/${resourceType.ownerRoleName}/memberEmails/${testUser.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/resources/v1/{resourceType}/{resourceId}/policies/{policyName}/public" should "200 if user has read_policies" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.user, samRequestContext))

    Get(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/public") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK withClue responsePayloadClue(responseAs[String])
      responseAs[Boolean] should equal(false)
    }
  }

  it should "200 if user has read_policy::" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readPolicy),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicy(AccessPolicyName("owner"))))),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.user, samRequestContext))

    Get(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/public") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK withClue responsePayloadClue(responseAs[String])
      responseAs[Boolean] should equal(false)
    }
  }

  it should "403 if user cannot read policies" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.delete),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.delete))),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.user, samRequestContext))

    Get(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/public") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden withClue responsePayloadClue(responseAs[String])
    }
  }

  "PUT /api/resources/v1/{resourceType}/{resourceId}/policies/{policyName}/public" should "204 if user has alter_policies and set_public" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(
      samRoutes.resourceService.addSubjectToPolicy(
        model.FullyQualifiedPolicyId(
          model.FullyQualifiedResourceId(TestSamRoutes.resourceTypeAdmin.name, ResourceId(resourceType.name.value)),
          AccessPolicyName(TestSamRoutes.resourceTypeAdmin.ownerRoleName.value)
        ),
        samRoutes.user.id,
        samRequestContext
      )
    )

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.user, samRequestContext))

    Put(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/public", true) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent withClue responsePayloadClue(responseAs[String])
    }
  }

  it should "204 if user has share_policy:: and set_public::" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.sharePolicy, SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.sharePolicy(AccessPolicyName("owner")), SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )

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
          Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies, SamResourceActions.setPublicPolicy(AccessPolicyName("owner")))
        )
      ),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(
      samRoutes.resourceService.addSubjectToPolicy(
        model.FullyQualifiedPolicyId(
          model.FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)),
          AccessPolicyName(resourceTypeAdmin.ownerRoleName.value)
        ),
        samRoutes.user.id,
        samRequestContext
      )
    )

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.user, samRequestContext))

    Put(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/public", true) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent withClue responsePayloadClue(responseAs[String])
    }
  }

  it should "403 if user does not have policy access" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(
      samRoutes.resourceService.addSubjectToPolicy(
        model.FullyQualifiedPolicyId(
          model.FullyQualifiedResourceId(TestSamRoutes.resourceTypeAdmin.name, ResourceId(resourceType.name.value)),
          AccessPolicyName(TestSamRoutes.resourceTypeAdmin.ownerRoleName.value)
        ),
        samRoutes.user.id,
        samRequestContext
      )
    )

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.user, samRequestContext))

    Put(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/public", true) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden withClue responsePayloadClue(responseAs[String])
    }
  }

  it should "404 if user does not have set public access" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.alterPolicies, SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.user, samRequestContext))

    Put(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/public", true) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound withClue responsePayloadClue(responseAs[String])
    }
  }

  "GET /api/resources/v1/{resourceType}/{resourceId}/authDomain" should "200 with auth domain if auth domain is set and user has read_auth_domain" in {
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
    val policiesMap = Map(
      AccessPolicyName("ap") -> AccessPolicyMembershipRequest(
        Set(defaultUserInfo.email),
        Set(SamResourceActions.readAuthDomain, ManagedGroupService.useAction),
        Set(ResourceRoleName("owner"))
      )
    )
    runAndWait(
      samRoutes.resourceService
        .createResource(resourceType, resourceId, policiesMap, Set(WorkbenchGroupName(authDomain)), None, defaultUserInfo.id, samRequestContext)
    )

    Get(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/authDomain") ~> samRoutes.route ~> check {
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
    val policiesMap = Map(
      AccessPolicyName("ap") -> AccessPolicyMembershipRequest(
        Set(defaultUserInfo.email),
        Set(SamResourceActions.readAuthDomain, ManagedGroupService.useAction),
        Set(ResourceRoleName("owner"))
      )
    )
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, policiesMap, Set.empty, None, defaultUserInfo.id, samRequestContext))

    Get(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/authDomain") ~> samRoutes.route ~> check {
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
    val policiesMap =
      Map(
        AccessPolicyName("ap") -> AccessPolicyMembershipRequest(Set(defaultUserInfo.email), Set(ManagedGroupService.useAction), Set(ResourceRoleName("owner")))
      )
    runAndWait(
      samRoutes.resourceService
        .createResource(resourceType, resourceId, policiesMap, Set(WorkbenchGroupName(authDomain)), None, defaultUserInfo.id, samRequestContext)
    )

    Get(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/authDomain") ~> samRoutes.route ~> check {
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
    val policiesMap = Map(
      AccessPolicyName("ap") -> AccessPolicyMembershipRequest(
        Set(defaultUserInfo.email),
        Set(SamResourceActions.readAuthDomain, ManagedGroupService.useAction),
        Set(ResourceRoleName("owner"))
      )
    )
    runAndWait(
      samRoutes.resourceService
        .createResource(resourceType, resourceId, policiesMap, Set(WorkbenchGroupName(authDomain)), None, defaultUserInfo.id, samRequestContext)
    )

    Get(s"/api/resources/v1/fakeResourceTypeName/$resourceId/authDomain") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }

    Get(s"/api/resources/v1/${resourceType.name}/fakeResourceId/authDomain") ~> samRoutes.route ~> check {
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
    val policiesMap = Map(
      AccessPolicyName("ap") -> AccessPolicyMembershipRequest(
        Set(defaultUserInfo.email),
        Set(SamResourceActions.readAuthDomain, ManagedGroupService.useAction),
        Set(ResourceRoleName("owner"))
      )
    )
    runAndWait(
      samRoutes.resourceService
        .createResource(resourceType, resourceId, policiesMap, Set(WorkbenchGroupName(authDomain)), None, defaultUserInfo.id, samRequestContext)
    )

    Get(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/authDomain") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[String]] shouldEqual Set(authDomain)
    }

    val otherUserSamRoutes = TestSamRoutes(Map(resourceType.name -> resourceType), Generator.genWorkbenchUserGoogle.sample.get)

    Get(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/authDomain") ~> otherUserSamRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  private def initManagedGroupResourceType(): ResourceType = {
    val accessPolicyNames = Set(ManagedGroupService.adminPolicyName, ManagedGroupService.memberPolicyName, ManagedGroupService.adminNotifierPolicyName)
    val policyActions: Set[ResourceAction] =
      accessPolicyNames.flatMap(policyName => Set(SamResourceActions.sharePolicy(policyName), SamResourceActions.readPolicy(policyName)))
    val resourceActions = Set(
      ResourceAction("delete"),
      ResourceAction("notify_admins"),
      ResourceAction("set_access_instructions"),
      ManagedGroupService.useAction
    ) union policyActions
    val resourceActionPatterns = resourceActions.map(action => ResourceActionPattern(action.value, "", false))
    val defaultOwnerRole = ResourceRole(ManagedGroupService.adminRoleName, resourceActions)
    val defaultMemberRole = ResourceRole(ManagedGroupService.memberRoleName, Set.empty)
    val defaultAdminNotifierRole = ResourceRole(ManagedGroupService.adminNotifierRoleName, Set(ResourceAction("notify_admins")))
    val defaultRoles = Set(defaultOwnerRole, defaultMemberRole, defaultAdminNotifierRole)

    ResourceType(ManagedGroupService.managedGroupTypeName, resourceActionPatterns, defaultRoles, ManagedGroupService.adminRoleName)
  }

  "GET /api/resources/v1/{resourceTypeName}/{resourceId}/allUsers" should "200 with all users list when user has read_policies action" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.readPolicies),
      Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicies))),
      ResourceRoleName("owner")
    )
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.user, samRequestContext))

    val user = samRoutes.userService.directoryDAO.loadUser(samRoutes.user.id, samRequestContext).unsafeRunSync().get
    val userIdInfo = UserIdInfo(user.id, user.email, user.googleSubjectId)

    Get(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/allUsers") ~> samRoutes.route ~> check {
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
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.user, samRequestContext))

    Get(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/allUsers") ~> samRoutes.route ~> check {
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
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.user, samRequestContext))

    Get(s"/api/resources/v1/fakeResourceTypeName/${resourceId.value}/allUsers") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }

    Get(s"/api/resources/v1/${resourceType.name}/fakeResourceId/allUsers") ~> samRoutes.route ~> check {
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
    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, samRoutes.user, samRequestContext))

    val user = samRoutes.userService.directoryDAO.loadUser(samRoutes.user.id, samRequestContext).unsafeRunSync().get
    val userIdInfo = UserIdInfo(user.id, user.email, user.googleSubjectId)

    Get(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/allUsers") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[UserIdInfo]].map(_.userSubjectId) shouldEqual Set(userIdInfo.userSubjectId)
    }

    val otherUserSamRoutes = TestSamRoutes(Map(resourceType.name -> resourceType), Generator.genWorkbenchUserGoogle.sample.get)

    Get(s"/api/resources/v1/${resourceType.name}/${resourceId.value}/allUsers") ~> otherUserSamRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }
}
