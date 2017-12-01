package org.broadinstitute.dsde.workbench.sam.google

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.google.mock.{MockGoogleDirectoryDAO, MockGoogleIamDAO, MockGooglePubSubDAO}
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes
import org.broadinstitute.dsde.workbench.sam.config.{GoogleServicesConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.{NoExtensions, ResourceService, StatusService, UserService}
import org.scalatest.{FlatSpec, Matchers}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.sam.openam.MockAccessPolicyDAO
import spray.json.{JsBoolean, JsValue}

/**
  * Unit tests of GoogleExtensionRoutes. Can use real Google services. Must mock everything else.
  */
class GoogleExtensionRoutesSpec extends FlatSpec with Matchers with ScalatestRouteTest {
  val defaultUserId = WorkbenchUserId("newuser")
  val defaultUserEmail = WorkbenchEmail("newuser@new.com")

  lazy val config = ConfigFactory.load()
  lazy val petServiceAccountConfig = config.as[PetServiceAccountConfig]("petServiceAccount")
  lazy val googleServicesConfig = config.as[GoogleServicesConfig]("googleServices")

  def withDefaultRoutes[T](testCode: TestSamRoutes => T): T = {
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val googleIamDAO = new MockGoogleIamDAO()

    val googleExt = new GoogleExtensions(directoryDAO, null, googleDirectoryDAO, null, googleIamDAO, googleServicesConfig, petServiceAccountConfig)
    val samRoutes = new TestSamRoutes(null, new UserService(directoryDAO, googleExt), new StatusService(directoryDAO, NoExtensions), UserInfo("", defaultUserId, defaultUserEmail, 0)) with GoogleExtensionRoutes {
      val googleExtensions = googleExt
    }
    testCode(samRoutes)
  }

  "GET /api/google/user/petServiceAccount" should "get or create a pet service account for a user" in withDefaultRoutes { samRoutes =>
    // create a user
    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    // create a pet service account
    Get("/api/google/user/petServiceAccount/myproject") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith (s"@myproject.iam.gserviceaccount.com")
    }

    // same result a second time
    Get("/api/google/user/petServiceAccount/myproject") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith (s"@myproject.iam.gserviceaccount.com")
    }
  }

  "POST /api/google/policy/{resourceTypeName}/{resourceId}/{accessPolicyName}/sync" should "204 Create Google group for policy" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("alter_policies"), ResourceAction("can_compute"), ResourceAction("read_policies")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("alter_policies"), ResourceAction("read_policies")))), ResourceRoleName("owner"))
    val resourceTypes = Map(resourceType.name -> resourceType)
    val defaultUserInfo = UserInfo("accessToken", WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val googleIamDAO = new MockGoogleIamDAO()
    val policyDAO = new MockAccessPolicyDAO()
    val pubSubDAO = new MockGooglePubSubDAO()
    val googleExt = new GoogleExtensions(directoryDAO, policyDAO, googleDirectoryDAO, pubSubDAO, googleIamDAO, googleServicesConfig, petServiceAccountConfig)
    googleExt.onBoot()
    val mockResourceService = new ResourceService(resourceTypes, policyDAO, directoryDAO, googleExt, "example.com")
    val samRoutes = new TestSamRoutes(mockResourceService, new UserService(directoryDAO, googleExt), new StatusService(directoryDAO, NoExtensions), UserInfo("", defaultUserInfo.userId, defaultUserInfo.userEmail, 0)) with GoogleExtensionRoutes {
      val googleExtensions = googleExt
    }

    //create user
    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    import spray.json.DefaultJsonProtocol._
    val createdPolicy = Get(s"/api/resource/${resourceType.name}/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Seq[AccessPolicyResponseEntry]].find(_.policyName == AccessPolicyName(resourceType.ownerRoleName.value)).getOrElse(fail("created policy not returned by get request"))
    }

    import GoogleModelJsonSupport._
    Post(s"/api/google/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      assertResult(Map(createdPolicy.email -> Seq(SyncReportItem("added", googleExt.toProxyFromUser(defaultUserInfo.userId.value), None)))) {
        responseAs[Map[WorkbenchEmail, Seq[SyncReportItem]]]
      }
    }
  }

  "GET /api/google/policy/{resourceTypeName}/{resourceId}/{accessPolicyName}/sync" should "200 with sync date" in {
    val resourceType = ResourceType(ResourceTypeName("rt"), Set(ResourceAction("alter_policies"), ResourceAction("can_compute"), ResourceAction("read_policies")), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("alter_policies"), ResourceAction("read_policies")))), ResourceRoleName("owner"))
    val resourceTypes = Map(resourceType.name -> resourceType)
    val defaultUserInfo = UserInfo("accessToken", WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val googleIamDAO = new MockGoogleIamDAO()
    val policyDAO = new MockAccessPolicyDAO()
    val pubSubDAO = new MockGooglePubSubDAO()
    val googleExt = new GoogleExtensions(directoryDAO, policyDAO, googleDirectoryDAO, pubSubDAO, googleIamDAO, googleServicesConfig, petServiceAccountConfig)
    googleExt.onBoot()
    val mockResourceService = new ResourceService(resourceTypes, policyDAO, directoryDAO, googleExt, "example.com")
    val samRoutes = new TestSamRoutes(mockResourceService, new UserService(directoryDAO, googleExt), new StatusService(directoryDAO, NoExtensions), UserInfo("", defaultUserInfo.userId, defaultUserInfo.userEmail, 0)) with GoogleExtensionRoutes {
      val googleExtensions = googleExt
    }

    //create user
    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    Post(s"/api/resource/${resourceType.name}/foo") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
      assertResult("") {
        responseAs[String]
      }
    }

    Get(s"/api/google/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
      assertResult("") {
        responseAs[String]
      }
    }

    Post(s"/api/google/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    import spray.json.DefaultJsonProtocol._
    val createdPolicy = Get(s"/api/resource/${resourceType.name}/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[Seq[AccessPolicyResponseEntry]]
      response.find(_.policyName == AccessPolicyName(resourceType.ownerRoleName.value)).getOrElse(fail("created policy not returned by get request"))
    }

    Get(s"/api/google/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] should not be empty
    }
  }
}
