package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LivenessRoutesSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with TestSupport {
  val livenessRoutes = new LivenessRoutes

  "GET /liveness" should "give 200" in {
    eventually {
      Get("/liveness") ~> livenessRoutes.route ~> check {
        status shouldEqual StatusCodes.OK
      }
    }
  }
}
