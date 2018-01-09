package org.broadinstitute.dsde.workbench.sam.google

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.google.mock.{MockGoogleDirectoryDAO, MockGoogleIamDAO, MockGooglePubSubDAO}
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.model.google.GoogleModelJsonSupport._
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes
import org.broadinstitute.dsde.workbench.sam.config.{GoogleServicesConfig, PetServiceAccountConfig}
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.scalatest.{FlatSpec, Matchers}
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.config._
import org.broadinstitute.dsde.workbench.sam.openam.MockAccessPolicyDAO
import spray.json.{JsBoolean, JsValue}

/**
  * Unit tests of GoogleExtensionRoutes. Can use real Google services. Must mock everything else.
  */
class GoogleExtensionRoutesSpec extends FlatSpec with Matchers with ScalatestRouteTest with TestSupport {
  val defaultUserId = WorkbenchUserId("newuser")
  val defaultUserEmail = WorkbenchEmail("newuser@new.com")

  lazy val config = ConfigFactory.load()
  lazy val petServiceAccountConfig = config.as[PetServiceAccountConfig]("petServiceAccount")
  lazy val googleServicesConfig = config.as[GoogleServicesConfig]("googleServices")

  val configResourceTypes = config.as[Set[ResourceType]]("resourceTypes").map(rt => rt.name -> rt).toMap

  def withDefaultRoutes[T](testCode: TestSamRoutes => T): T = {
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val googleIamDAO = new MockGoogleIamDAO()

    val googleExt = new GoogleExtensions(directoryDAO, null, googleDirectoryDAO, null, googleIamDAO, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))
    val samRoutes = new TestSamRoutes(null, new UserService(directoryDAO, googleExt), new StatusService(directoryDAO, NoExtensions), UserInfo("", defaultUserId, defaultUserEmail, 0), directoryDAO) with GoogleExtensionRoutes {
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

  "GET /api/google/user/proxyGroup/{email}" should "return a user's proxy group" in withDefaultRoutes { samRoutes =>
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val googleIamDAO = new MockGoogleIamDAO()
    val policyDAO = new MockAccessPolicyDAO()
    val pubSubDAO = new MockGooglePubSubDAO()
    val googleExt = new GoogleExtensions(directoryDAO, policyDAO, googleDirectoryDAO, pubSubDAO, googleIamDAO, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))

    // create a user
    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Get(s"/api/google/user/proxyGroup/$defaultUserEmail") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response shouldBe googleExt.toProxyFromUser(defaultUserId)
    }
  }
  private val name = ResourceType(ResourceTypeName("rt"), Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute"), SamResourceActionPatterns.readPolicies), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("alter_policies"), ResourceAction("read_policies")))), ResourceRoleName("owner"))
  private val resourceType = ResourceType(ResourceTypeName("rt"), Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute"), SamResourceActionPatterns.readPolicies), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("alter_policies"), ResourceAction("read_policies")))), ResourceRoleName("owner"))

  "POST /api/google/policy/{resourceTypeName}/{resourceId}/{accessPolicyName}/sync" should "204 Create Google group for policy" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val defaultUserInfo = UserInfo("accessToken", WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val googleIamDAO = new MockGoogleIamDAO()
    val policyDAO = new MockAccessPolicyDAO()
    val pubSubDAO = new MockGooglePubSubDAO()
    val googleExt = new GoogleExtensions(directoryDAO, policyDAO, googleDirectoryDAO, pubSubDAO, googleIamDAO, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))

    val mockResourceService = new ResourceService(resourceTypes, policyDAO, directoryDAO, googleExt, "example.com")
    val samRoutes = new TestSamRoutes(mockResourceService, new UserService(directoryDAO, googleExt), new StatusService(directoryDAO, NoExtensions), UserInfo("", defaultUserInfo.userId, defaultUserInfo.userEmail, 0), directoryDAO) with GoogleExtensionRoutes {
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
      assertResult(Map(createdPolicy.email -> Seq(SyncReportItem("added", googleExt.toProxyFromUser(defaultUserInfo.userId).value, None)))) {
        responseAs[Map[WorkbenchEmail, Seq[SyncReportItem]]]
      }
    }
  }

  "GET /api/google/policy/{resourceTypeName}/{resourceId}/{accessPolicyName}/sync" should "200 with sync date" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val defaultUserInfo = UserInfo("accessToken", WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val googleIamDAO = new MockGoogleIamDAO()
    val policyDAO = new MockAccessPolicyDAO()
    val pubSubDAO = new MockGooglePubSubDAO()
    val googleExt = new GoogleExtensions(directoryDAO, policyDAO, googleDirectoryDAO, pubSubDAO, googleIamDAO, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))

    val mockResourceService = new ResourceService(resourceTypes, policyDAO, directoryDAO, googleExt, "example.com")
    val samRoutes = new TestSamRoutes(mockResourceService, new UserService(directoryDAO, googleExt), new StatusService(directoryDAO, NoExtensions), UserInfo("", defaultUserInfo.userId, defaultUserInfo.userEmail, 0), directoryDAO) with GoogleExtensionRoutes {
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

  "GET /api/google/user/petServiceAccount/{project}/key" should "200 with a new key" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val defaultUserInfo = UserInfo("accessToken", WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val googleIamDAO = new MockGoogleIamDAO()
    val policyDAO = new MockAccessPolicyDAO()
    val pubSubDAO = new MockGooglePubSubDAO()
    val googleExt = new GoogleExtensions(directoryDAO, policyDAO, googleDirectoryDAO, pubSubDAO, googleIamDAO, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))

    val mockResourceService = new ResourceService(resourceTypes, policyDAO, directoryDAO, googleExt, "example.com")
    val samRoutes = new TestSamRoutes(mockResourceService, new UserService(directoryDAO, googleExt), new StatusService(directoryDAO, NoExtensions), UserInfo("", defaultUserInfo.userId, defaultUserInfo.userEmail, 0), directoryDAO) with GoogleExtensionRoutes {
      val googleExtensions = googleExt
    }

    //create user
    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    // create a pet service account
    Get("/api/google/user/petServiceAccount/myproject") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith (s"@myproject.iam.gserviceaccount.com")
    }

    // create a pet service account key
    Get("/api/google/user/petServiceAccount/myproject/key") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[ServiceAccountKey]
      assert(response.validBefore.isDefined)
    }
  }

  "DELETE /api/google/user/petServiceAccount/{project}/key/{keyId}" should "204 when deleting a key" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val defaultUserInfo = UserInfo("accessToken", WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val googleIamDAO = new MockGoogleIamDAO()
    val policyDAO = new MockAccessPolicyDAO()
    val pubSubDAO = new MockGooglePubSubDAO()
    val googleExt = new GoogleExtensions(directoryDAO, policyDAO, googleDirectoryDAO, pubSubDAO, googleIamDAO, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))

    val mockResourceService = new ResourceService(resourceTypes, policyDAO, directoryDAO, googleExt, "example.com")
    val samRoutes = new TestSamRoutes(mockResourceService, new UserService(directoryDAO, googleExt), new StatusService(directoryDAO, NoExtensions), UserInfo("", defaultUserInfo.userId, defaultUserInfo.userEmail, 0), directoryDAO) with GoogleExtensionRoutes {
      val googleExtensions = googleExt
    }

    //create user
    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    // create a pet service account
    Get("/api/google/user/petServiceAccount/myproject") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith (s"@myproject.iam.gserviceaccount.com")
    }

    // create a pet service account key
    Get("/api/google/user/petServiceAccount/myproject/key") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[ServiceAccountKey]
      assert(response.validBefore.isDefined)
    }

    // create a pet service account key
    Delete("/api/google/user/petServiceAccount/myproject/key/123") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  private def setupPetSATest: (UserInfo, TestSamRoutes with GoogleExtensionRoutes) = {
    val defaultUserInfo = UserInfo("accessToken", WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val googleIamDAO = new MockGoogleIamDAO()
    val policyDAO = new MockAccessPolicyDAO()
    val pubSubDAO = new MockGooglePubSubDAO()
    val googleExt = new GoogleExtensions(directoryDAO, policyDAO, googleDirectoryDAO, pubSubDAO, googleIamDAO, googleServicesConfig.copy(serviceAccountClientEmail = defaultUserInfo.userEmail.value, serviceAccountClientId = defaultUserInfo.userId.value), petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))

    val mockResourceService = new ResourceService(configResourceTypes, policyDAO, directoryDAO, googleExt, "example.com")
    val userService = new UserService(directoryDAO, googleExt)
    val statusService = new StatusService(directoryDAO, NoExtensions)
    val samRoutes = new TestSamRoutes(mockResourceService, userService, statusService, UserInfo("", defaultUserInfo.userId, defaultUserInfo.userEmail, 0), directoryDAO) with GoogleExtensionRoutes {
      val googleExtensions = googleExt
    }

    runAndWait(googleExt.onBoot(SamApplication(userService, mockResourceService, statusService)))
    (defaultUserInfo, samRoutes)
  }

  "GET /api/google/petServiceAccount/{project}/{userEmail}" should "200 with a key" in {
    val (defaultUserInfo, samRoutes) = setupPetSATest

    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(GoogleExtensions.getPetPrivateKeyAction), Set.empty)
    Put(s"/api/resource/${CloudExtensions.resourceTypeName.value}/${GoogleExtensions.resourceId.value}/policies/foo", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    // create a pet service account key
    Get(s"/api/google/petServiceAccount/myproject/${defaultUserInfo.userEmail.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[ServiceAccountKey]
      assert(response.validBefore.isDefined)
    }
  }

  it should "404 when user does not exist" in {
    val (defaultUserInfo, samRoutes) = setupPetSATest

    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(GoogleExtensions.getPetPrivateKeyAction), Set.empty)
    Put(s"/api/resource/${CloudExtensions.resourceTypeName.value}/${GoogleExtensions.resourceId.value}/policies/foo", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    // create a pet service account key
    Get(s"/api/google/petServiceAccount/myproject/I-do-not-exist@foo.bar") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "403 when caller does not have action" in {
    val (defaultUserInfo, samRoutes) = setupPetSATest

    // create a pet service account key
    Get(s"/api/google/petServiceAccount/myproject/I-do-not-exist@foo.bar") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }
}
