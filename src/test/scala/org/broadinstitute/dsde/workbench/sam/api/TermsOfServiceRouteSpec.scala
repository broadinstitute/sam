package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, databaseEnabledClue}
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TermsOfServiceRouteSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with TestSupport {

  "GET /tos/text" should "return the tos text" in {
    assume(databaseEnabled, databaseEnabledClue)

    val samRoutes = TestSamRoutes(Map.empty)
    eventually {
      Get("/tos/text") ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String].isEmpty shouldBe false
      }
    }
  }

  "GET /privacy/text" should "return the privacy policy text" in {
    val samRoutes = TestSamRoutes(Map.empty)
    eventually {
      Get("/privacy/text") ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String].isEmpty shouldBe false
      }
    }
  }

}
