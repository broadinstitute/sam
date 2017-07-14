package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.{ResourceService, UserService}
import org.scalatest.{FlatSpec, Matchers}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.ErrorReportJsonSupport._

/**
  * Created by dvoet on 6/7/17.
  */
class ResourceRoutesSpec extends FlatSpec with Matchers with ScalatestRouteTest {

  "ResourceRoutes" should "404 for unknown resource type" in {
    val samRoutes = new TestSamRoutes(Map.empty, null, null, UserInfo("", SamUserId(""), SamUserEmail(""), 0))

    Get("/api/resource/foo/bar/action") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
      responseAs[ErrorReport].message shouldEqual "resource type foo not found"
    }
  }

  it should "list all resource types" in {
    val samRoutes = new TestSamRoutes(Map.empty, null, null, UserInfo("", SamUserId(""), SamUserEmail(""), 0))

    Get("/api/resourceTypes") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }
}

