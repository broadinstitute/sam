package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.scalatest.{FlatSpec, Matchers}

/**
  * Created by dvoet on 6/7/17.
  */
class GroupRoutesSpec extends FlatSpec with Matchers with ScalatestRouteTest with TestSupport {

  "GET api/group" should "not exist" in {
    val samRoutes = TestSamRoutes(Map.empty)

    intercept[Exception] {
      Get("/api/group") ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.OK
      }
    }
  }

  "GET /api/group/{groupName}" should "return a flattened list of users who are in this group" in {
    val samRoutes = TestSamRoutes(Map.empty)

    Get("/api/group/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] should include ("Group Name: foo")
    }
  }

  "GET /api/group/{groupName}/owners" should "return the flattened list of users who are owners of this group" is pending

  "GET /api/group/{groupName}/members" should "return the flattened list of users who are non-owner members of this group" is pending

  "POST /api/group/{groupName}" should "create a new managed group, the owner group, and the members group, and the All Group and all policies for those groups" in {
    val samRoutes = TestSamRoutes(Map.empty)

    Post("/api/group/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] should include ("Posted new Group: foo")
    }
  }

  "DELETE /api/group/{groupName}" should "delete the group, member groups, and all associated policies when the authenticated user is an owner of the group" in {
    val samRoutes = TestSamRoutes(Map.empty)

    Delete("/api/group/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] should include ("Deleted Group: foo")
    }
  }

  it should "fail if the authenticated user user is not an owner of the group" is pending


}

