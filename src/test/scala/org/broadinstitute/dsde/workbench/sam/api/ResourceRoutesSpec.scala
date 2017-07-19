package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.{ResourceService, UserService}
import org.scalatest.{FlatSpec, Matchers}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.ErrorReportJsonSupport._
import org.broadinstitute.dsde.workbench.sam.openam.MockAccessPolicyDAO
import spray.json.{JsBoolean, JsValue}

/**
  * Created by dvoet on 6/7/17.
  */
class ResourceRoutesSpec extends FlatSpec with Matchers with ScalatestRouteTest {

  private def createSamRoutes(resourceTypes: Map[ResourceTypeName, ResourceType]) = {
    val mockResourceService = new ResourceService(new MockAccessPolicyDAO(), new MockDirectoryDAO())
    val mockUserService = new UserService(new MockDirectoryDAO())

    new TestSamRoutes(resourceTypes, mockResourceService, mockUserService, UserInfo("", SamUserId(""), SamUserEmail(""), 0))
  }

  "ResourceRoutes" should "404 for unknown resource type" in {
    val samRoutes = createSamRoutes(Map.empty)

    Get("/api/resource/foo/bar/action") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
      responseAs[ErrorReport].message shouldEqual "resource type foo not found"
    }
  }

  it should "list all resource types" in {
    val samRoutes = createSamRoutes(Map.empty)

    Get("/api/resourceTypes") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  it should "204 create resource" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("run")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("run")))), ResourceRoleName("owner"))
    val samRoutes = createSamRoutes(Map(resourceType.name -> resourceType))

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    Get(s"/api/resource/${resourceType.name}/foo/action/run") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[JsValue] shouldEqual JsBoolean(true)
    }

  }
}

