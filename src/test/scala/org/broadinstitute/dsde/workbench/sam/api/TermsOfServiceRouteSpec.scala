package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, databaseEnabledClue}
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.api.{SamUser, TermsOfServiceConfigResponse}
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, TermsOfServiceDetails}
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class TermsOfServiceRouteSpec extends AnyFunSpec with Matchers with ScalatestRouteTest with TestSupport {

  describe("GET /tos/text") {
    it("return the tos text") {
      assume(databaseEnabled, databaseEnabledClue)

      val samRoutes = TestSamRoutes(Map.empty)
      eventually {
        Get("/tos/text") ~> samRoutes.route ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[String].isEmpty shouldBe false
        }
      }
    }
  }

  describe("GET /privacy/text") {
    it("return the privacy policy text") {
      val samRoutes = TestSamRoutes(Map.empty)
      eventually {
        Get("/privacy/text") ~> samRoutes.route ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[String].isEmpty shouldBe false
        }
      }
    }
  }

  describe("GET /api/termsOfService/v1") {
    it("return the current tos config") {
      val samRoutes = TestSamRoutes(Map.empty)
      eventually {
        Get("/api/termsOfService/v1") ~> samRoutes.route ~> check {
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
  }

  describe("GET /api/termsOfService/v1/docs") {
    val samRoutes = TestSamRoutes(Map.empty)
    it("should return the terms of service text when no query parameters are passed") {
      Get("/api/termsOfService/v1/docs") ~> samRoutes.route ~> check {
        responseAs[String] shouldBe samRoutes.tosService.termsOfServiceText
        status shouldBe StatusCodes.OK
      }
    }
    it("should return the terms of service text when 'termsOfService' is passed as a query param.") {
      Get(Uri("/api/termsOfService/v1/docs").withQuery(Uri.Query("doc=termsOfService"))) ~> samRoutes.route ~> check {
        responseAs[String] shouldBe samRoutes.tosService.termsOfServiceText
        status shouldBe StatusCodes.OK
      }
    }
    it("should return the privacy policy text when 'privacyPolicy' is passed as a query param.") {
      Get(Uri("/api/termsOfService/v1/docs").withQuery(Uri.Query("doc=privacyPolicy"))) ~> samRoutes.route ~> check {
        responseAs[String] shouldBe samRoutes.tosService.privacyPolicyText
        status shouldBe StatusCodes.OK
      }
    }
    it("should return the terms of service text and privacy policy text when 'termsOfService,privacyPolicy' is passed as a query param.") {
      Get(Uri("/api/termsOfService/v1/docs").withQuery(Uri.Query("doc=termsOfService,privacyPolicy"))) ~> samRoutes.route ~> check {
        responseAs[String] shouldBe s"${samRoutes.tosService.termsOfServiceText}\n\n${samRoutes.tosService.privacyPolicyText}"
        status shouldBe StatusCodes.OK
      }
    }
  }

  describe("GET /api/termsOfService/v1/docs/redirect") {
    val samRoutes = TestSamRoutes(Map.empty)
    it("should be a valid route") {
      Get("/api/termsOfService/v1/docs/redirect") ~> samRoutes.route ~> check {
        status shouldBe StatusCodes.NotImplemented
      }
    }
  }

  describe("GET /api/termsOfService/v1/user") {
    val samRoutes = TestSamRoutes(Map.empty)
    it("should not be handled") {
      Get("/api/termsOfService/v1/user") ~> samRoutes.route ~> check {
        assert(!handled, "`GET /api/termsOfService/v1/user` should not be a handled route")
      }
    }
  }

  describe("GET /api/termsOfService/v1/user/self") {
    val samRoutes = TestSamRoutes(Map.empty)
    it("should return an instance of `TermsOfServiceDetails`") {
      Get("/api/termsOfService/v1/user/self") ~> samRoutes.route ~> check {
        withClue(s"${responseAs[String]} is not parsable as an instance of `TermsOfServiceDetails`.") {
          responseAs[TermsOfServiceDetails]
        }
        status shouldEqual StatusCodes.OK
      }
    }
  }

  describe("GET /api/termsOfService/v1/user/{USER_ID}") {
    val samRoutes = TestSamRoutes(Map.empty)
    it("should return an instance of `TermsOfServiceDetails`") {
      val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))
      val defaultUser: SamUser = Generator.genWorkbenchUserGoogle.sample.get
      val mockSamRoutesBuilder = new MockSamRoutesBuilder(allUsersGroup)
        .withEnabledUser(defaultUser)
        .withTosStateForUser(defaultUser, isAccepted = true, "0")

      Get(s"/api/termsOfService/v1/user/${defaultUser.id}") ~> mockSamRoutesBuilder.build.route ~> check {
        withClue(s"${responseAs[String]} is not parsable as an instance of `TermsOfServiceDetails`.") {
          responseAs[TermsOfServiceDetails]
        }
        status shouldEqual StatusCodes.OK
      }
    }

    it("should return 404 when USER_ID is does not exist") {
      val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))
      val mockSamRoutesBuilder = new MockSamRoutesBuilder(allUsersGroup)
        .withEnabledUser(Generator.genWorkbenchUserGoogle.sample.get)

      Get("/api/termsOfService/v1/user/12345abc") ~> mockSamRoutesBuilder.build.route ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    it("should return 400 when called with an invalidly formatted USER_ID") {
      Get("/api/termsOfService/v1/user/bad!_str~ng") ~> Route.seal(samRoutes.route) ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
    }
  }

  it("should return 204 when tos accepted") {
    val samRoutes = TestSamRoutes(Map.empty)
    eventually {
      Put("/api/termsOfService/v1/user/self/accept") ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.NoContent
        responseAs[String] shouldBe ""
      }
    }
  }
}
