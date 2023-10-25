package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, databaseEnabledClue}
import org.broadinstitute.dsde.workbench.sam.model.SamUserTos
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TermsOfServiceRouteSpec extends AnyFlatSpec with Matchers with ScalatestRouteTest with TestSupport {
  val samRoutes: TestSamRoutes = TestSamRoutes(Map.empty)

  "GET /tos/text" should "return the tos text" in {
    assume(databaseEnabled, databaseEnabledClue)

    eventually {
      Get("/tos/text") ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String].isEmpty shouldBe false
      }
    }
  }

  "GET /privacy/text" should "return the privacy policy text" in {
    Get("/privacy/text") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String].isEmpty shouldBe false
    }
  }

  "GET /termsOfService/v1" should "be a valid route" in {
    Get("/termsOfService/v1") ~> samRoutes.route ~> check {
      status shouldBe StatusCodes.NotImplemented
    }
  }

  "GET /termsOfService/v1/docs" should "be a valid route" in {
    Get("/termsOfService/v1/docs") ~> samRoutes.route ~> check {
      status shouldBe StatusCodes.NotImplemented
    }
  }

  "GET /termsOfService/v1/docs/redirect" should "be a valid route" in {
    Get("/termsOfService/v1/docs/redirect") ~> samRoutes.route ~> check {
      status shouldBe StatusCodes.NotImplemented
    }
  }

  "GET /api/termsOfService/v1/user" should "not be handled" in {
    Get("/api/termsOfService/v1/user") ~> samRoutes.route ~> check {
      assert(!handled, "`GET /api/termsOfService/v1/user` should not be a handled route")
    }
  }

  "GET /api/termsOfService/v1/user/self" should "return ToS status for the calling user" in {
    Get("/api/termsOfService/v1/user/self") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      withClue(s"${responseAs[String]} is not parsable as an instance of `SamUserTos`.") {
        responseAs[SamUserTos]
      }
    }
  }

  "GET /api/termsOfService/v1/user/{USER_ID}" should "return an instance of `SamUserTos`" in {
    Get("/api/termsOfService/v1/user/123") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      withClue(s"${responseAs[String]} is not parsable as an instance of `SamUserTos`.") {
        responseAs[SamUserTos]
      }
    }
  }

}
