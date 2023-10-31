package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, databaseEnabledClue}
import org.broadinstitute.dsde.workbench.sam.model.api.TermsOfServiceConfigResponse
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

class TermsOfServiceRouteSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with TestSupport {

  "GET /termsOfService/v1" should "return the current tos config" in {

    val samRoutes = TestSamRoutes(Map.empty)
    eventually {
      Get("/termsOfService/v1") ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[TermsOfServiceConfigResponse] shouldBe TermsOfServiceConfigResponse(
          enforced = true,
          currentVersion = "0",
          inGracePeriod = false,
          inRollingAcceptanceWindow = false
        )
      }
    }
  }

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
