package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, databaseEnabledClue}
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, SamUserTos}
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
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
    val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))
    val defaultUser: SamUser = Generator.genWorkbenchUserGoogle.sample.get
    val mockSamRoutesBuilder = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUser(defaultUser)
      .withAllowedUser(defaultUser)

    Get(s"/api/termsOfService/v1/user/${defaultUser.id}") ~> mockSamRoutesBuilder.build.route ~> check {
      withClue(s"${responseAs[String]} is not parsable as an instance of `SamUserTos`.") {
        responseAs[SamUserTos]
      }
      status shouldEqual StatusCodes.OK
    }
  }

  "GET /api/termsOfService/v1/user/{valid_but_non_existing_user_id}" should "return a 404" in {
    val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))
    val mockSamRoutesBuilder = new MockSamRoutesBuilder(allUsersGroup)
      .withEnabledUser(Generator.genWorkbenchUserGoogle.sample.get)

    Get("/api/termsOfService/v1/user/12345abc") ~> mockSamRoutesBuilder.build.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/termsOfService/v1/user/{invalid_sam_user_id_format}" should "return a 404" in {
    Get("/api/termsOfService/v1/user/bad!_str~ng") ~> Route.seal(samRoutes.route) ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

}
