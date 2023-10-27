package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, databaseEnabledClue}
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, TermsOfServiceDetails}
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class TermsOfServiceRouteSpec extends AnyFunSpec with Matchers with ScalatestRouteTest with TestSupport {
  val samRoutes: TestSamRoutes = TestSamRoutes(Map.empty)

  describe("GET /tos/text") {
    it("should return the tos text") {
      assume(databaseEnabled, databaseEnabledClue)

      eventually {
        Get("/tos/text") ~> samRoutes.route ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[String].isEmpty shouldBe false
        }
      }
    }
  }

  describe("GET /privacy/text") {
    it("should return the privacy policy text") {
      Get("/privacy/text") ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String].isEmpty shouldBe false
      }
    }
  }

  describe("GET /api/termsOfService/v1") {
    it("should be a valid route") {
      Get("/api/termsOfService/v1") ~> samRoutes.route ~> check {
        status shouldBe StatusCodes.NotImplemented
      }
    }
  }

  describe("GET /api/termsOfService/v1/docs") {
    it("should be a valid route") {
      Get("/api/termsOfService/v1/docs") ~> samRoutes.route ~> check {
        status shouldBe StatusCodes.NotImplemented
      }
    }
  }

  describe("GET /api/termsOfService/v1/docs/redirect") {
    it("should be a valid route") {
      Get("/api/termsOfService/v1/docs/redirect") ~> samRoutes.route ~> check {
        status shouldBe StatusCodes.NotImplemented
      }
    }
  }

  describe("GET /api/termsOfService/v1/user") {
    it("should not be handled") {
      Get("/api/termsOfService/v1/user") ~> samRoutes.route ~> check {
        assert(!handled, "`GET /api/termsOfService/v1/user` should not be a handled route")
      }
    }
  }

  describe("GET /api/termsOfService/v1/user/self") {
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

}
