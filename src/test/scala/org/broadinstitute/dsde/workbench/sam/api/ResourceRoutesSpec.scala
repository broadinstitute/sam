package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.sam.model._
import org.scalatest.{FlatSpec, Matchers}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.broadinstitute.dsde.workbench.google.mock.{MockGoogleDirectoryDAO, MockGoogleIamDAO}
import org.broadinstitute.dsde.workbench.google.model.GoogleProject
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchUserEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import spray.json.{JsBoolean, JsValue}
import spray.json.DefaultJsonProtocol._
import org.broadinstitute.dsde.workbench.model.ErrorReportJsonSupport._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config.PetServiceAccountConfig
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.openam.MockAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.service.{ResourceService, StatusService, UserService}

/**
  * Created by dvoet on 6/7/17.
  */
class ResourceRoutesSpec extends FlatSpec with Matchers with ScalatestRouteTest with TestSupport {

  val defaultUserInfo = UserInfo("accessToken", WorkbenchUserId("user1"), WorkbenchUserEmail("user1@example.com"), 0)

  private def createSamRoutes(resourceTypes: Map[ResourceTypeName, ResourceType], userInfo: UserInfo = defaultUserInfo) = {
    val accessPolicyDAO = new MockAccessPolicyDAO()
    val directoryDAO = new MockDirectoryDAO()
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val googleIamDAO = new MockGoogleIamDAO()
    val petServiceAccountConfig = PetServiceAccountConfig(GoogleProject("test-project"), Set(WorkbenchUserEmail("test@test.gserviceaccount.com")))

    val mockResourceService = new ResourceService(resourceTypes, accessPolicyDAO, directoryDAO, "example.com")
    val mockUserService = new UserService(directoryDAO, googleDirectoryDAO, googleIamDAO, "dev.test.firecloud.org", petServiceAccountConfig)
    val mockStatusService = new StatusService(directoryDAO, googleDirectoryDAO)

    new TestSamRoutes(mockResourceService, mockUserService, mockStatusService, userInfo)
  }

  "GET /api/resources/{resourceType}/{resourceId}/actions/{action}" should "404 for unknown resource type" in {
    val samRoutes = TestSamRoutes(Map.empty)

    Get("/api/resource/foo/bar/action") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
      responseAs[ErrorReport].message shouldEqual "resource type foo not found"
    }
  }
  
  "GET /api/resourceTypes" should "200 when listing all resource types" in {
    val samRoutes = TestSamRoutes(Map.empty)

    Get("/api/resourceTypes") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  "POST /api/resources/{resourceType}/{resourceId}" should "204 create resource" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("run")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/resource/${resourceType.name}/foo/action/run") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[JsValue] shouldEqual JsBoolean(true)
    }

  }

  "GET /api/resources/{resourceType}/{resourceId}/roles" should "200 on list resource roles" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("run")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))
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
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("run")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Get(s"/api/resource/doesntexist/foo/roles") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PUT /api/resources/{resourceType}/{resourceId}/policies" should "201 on a new policy being created for a resource" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("alterpolicies"), ResourceAction("cancompute")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("alterpolicies")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    val members = AccessPolicyMembership(Set("foo@bar.baz"), Set(ResourceAction("cancompute")), Set.empty)

    Put(s"/api/resource/${resourceType.name}/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }
  }

  it should "201 on a policy being updated" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("alterpolicies"), ResourceAction("cancompute")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("alterpolicies")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    val members = AccessPolicyMembership(Set("foo@bar.baz"), Set(ResourceAction("cancompute")), Set.empty)

    Put(s"/api/resource/${resourceType.name}/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    val members2 = AccessPolicyMembership(Set("foo@bar.baz"), Set(ResourceAction("cancompute")), Set(ResourceRoleName("owner")))

    Put(s"/api/resource/${resourceType.name}/foo/policies/canCompute", members2) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }
  }

  it should "400 on a policy being created with invalid actions" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("alterpolicies"), ResourceAction("cancompute")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("alterpolicies")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    val members = AccessPolicyMembership(Set("foo@bar.baz"), Set(ResourceAction("fakeaction")), Set.empty)

    Put(s"/api/resource/${resourceType.name}/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "400 on a policy being created with invalid roles" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("alterpolicies"), ResourceAction("cancompute")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("alterpolicies")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    val members = AccessPolicyMembership(Set("foo@bar.baz"), Set(ResourceAction("cancompute")), Set(ResourceRoleName("fakerole")))

    Put(s"/api/resource/${resourceType.name}/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "403 when creating a policy on a resource when the user doesn't have alterpolicies permission (but can see the resource)" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("alterpolicies"), ResourceAction("cancompute")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("cancompute")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    val members = AccessPolicyMembership(Set("me@me.me"), Set(ResourceAction("cancompute")), Set.empty)

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Put(s"/api/resource/${resourceType.name}/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when creating a policy on a resource type that doesnt exist" in {
    val samRoutes = TestSamRoutes(Map.empty)
    val members = AccessPolicyMembership(Set("foo@bar.baz"), Set(ResourceAction("cancompute")), Set.empty)

    //Create a resource of a type that doesn't exist
    Put(s"/api/resource/fakeresourcetype/foo/policies/canCompute", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 when creating a policy on a resource that the user doesnt have permission to see" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("alterpolicies"), ResourceAction("cancompute")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("alterpolicies")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    val otherUserSamRoutes = TestSamRoutes(Map(resourceType.name -> resourceType), UserInfo("accessToken", WorkbenchUserId("user2"), WorkbenchUserEmail("user2@example.com"), 0))
    val members = AccessPolicyMembership(Set("foo@bar.baz"), Set(ResourceAction("cancompute")), Set.empty)

    //Create a resource
    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    //As a different user who isn't on any policy, try to overwrite a policy
    Put(s"/api/resource/${resourceType.name}/foo/policies/canCompute", members) ~> otherUserSamRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/resources/{resourceType}/{resourceId}/policies" should "200 when listing policies for a resource and user has readpolicies permission" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("readpolicies"), ResourceAction("cancompute")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("readpolicies")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    //Create a resource
    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    //Read the policies
    Get(s"/api/resource/${resourceType.name}/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  it should "403 when listing policies for a resource and user lacks readpolicies permission (but can see the resource)" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("alterpolicies"), ResourceAction("cancompute")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("alterpolicies")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    //Create a resource that doesn't have the readpolicies action on any roles
    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    //Try to read the policies
    Get(s"/api/resource/${resourceType.name}/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when listing policies for a resource type that doesnt exist" in {
    val samRoutes = TestSamRoutes(Map.empty)

    //List policies for a bogus resource type
    Get(s"/api/resource/fakeresourcetype/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 when listing policies for a resource when user can't see the resource" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("alterpolicies"), ResourceAction("cancompute")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("alterpolicies")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    val otherUserSamRoutes = TestSamRoutes(Map(resourceType.name -> resourceType), UserInfo("accessToken", WorkbenchUserId("user2"), WorkbenchUserEmail("user2@example.com"), 0))

    //Create the resource
    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    //As a different user, try to read the policies
    Get(s"/api/resource/${resourceType.name}/foo/policies") ~> otherUserSamRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "DELETE /api/resources/{resourceType}/{resourceId}" should "204 when deleting a resource and the user has permission to do so" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("alterpolicies"), ResourceAction("readpolicies"), ResourceAction("delete")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("delete"), ResourceAction("readpolicies")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    //Create the resource
    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    //Read the policies to make sure the resource exists)
    Get(s"/api/resource/${resourceType.name}/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    //Delete the resource
    Delete(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "403 when deleting a resource and the user has permission to see the resource but not delete" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("alterpolicies"), ResourceAction("readpolicies")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("readpolicies")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    //Create the resource
    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    //Read the policies to make sure the resource exists)
    Get(s"/api/resource/${resourceType.name}/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    //Delete the resource
    Delete(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when deleting a resource of a type that doesn't exist" in {
    val samRoutes = TestSamRoutes(Map.empty)

    //Delete the resource
    Delete(s"/api/resource/INVALID_RESOURCE_TYPE/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 when deleting a resource that exists but can't be seen by the user" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("alterpolicies"), ResourceAction("readpolicies")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(samRoutes.resourceService.createResourceType(resourceType))
    runAndWait(samRoutes.resourceService.accessPolicyDAO.createResource(Resource(resourceType.name, ResourceId("foo"))))

    //Verify resource exists by checking for conflict on recreate
    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Conflict
    }

    //Delete the resource
    Delete(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/resources/{resourceType}" should "200" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("readpolicies"), ResourceAction("cancompute")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("readpolicies")))), ResourceRoleName("owner"))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    //Create a resource
    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    //Read the policies
    Get(s"/api/resource/${resourceType.name}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }
}

