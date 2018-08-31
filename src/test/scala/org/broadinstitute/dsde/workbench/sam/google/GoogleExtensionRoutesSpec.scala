package org.broadinstitute.dsde.workbench.sam.google

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.testkit.ScalatestRouteTest
import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.dataaccess.PubSubNotificationDAO
import org.broadinstitute.dsde.workbench.google.GoogleIamDAO
import org.broadinstitute.dsde.workbench.google.mock.{MockGoogleDirectoryDAO, MockGoogleIamDAO, MockGooglePubSubDAO, MockGoogleStorageDAO}
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes
import org.broadinstitute.dsde.workbench.sam.config.{GoogleServicesConfig, PetServiceAccountConfig, _}
import org.broadinstitute.dsde.workbench.sam.directory.MockDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.openam.MockAccessPolicyDAO
import org.broadinstitute.dsde.workbench.sam.service._
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Future

/**
  * Unit tests of GoogleExtensionRoutes. Can use real Google services. Must mock everything else.
  */
class GoogleExtensionRoutesSpec extends FlatSpec with Matchers with ScalatestRouteTest with TestSupport with MockitoSugar {
  val defaultUserId = WorkbenchUserId("newuser123")
  val defaultUserEmail = WorkbenchEmail("newuser@new.com")
/* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
  val defaultUserProxyEmail = WorkbenchEmail(s"newuser_$defaultUserId@${googleServicesConfig.appsDomain}")
*/
  val defaultUserProxyEmail = WorkbenchEmail(s"PROXY_$defaultUserId@${googleServicesConfig.appsDomain}")
/**/

  lazy val config = ConfigFactory.load()
  lazy val petServiceAccountConfig = config.as[PetServiceAccountConfig]("petServiceAccount")
  lazy val googleServicesConfig = config.as[GoogleServicesConfig]("googleServices")

  val configResourceTypes = config.as[Map[String, ResourceType]]("resourceTypes").values.map(rt => rt.name -> rt).toMap

  def withDefaultRoutes[T](testCode: TestSamRoutes => T): T = {
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val googleIamDAO = new MockGoogleIamDAO()
    val pubSubDAO = new MockGooglePubSubDAO()
    val googleStorageDAO = new MockGoogleStorageDAO()
    val notificationDAO = new PubSubNotificationDAO(pubSubDAO, "foo")
    val cloudKeyCache = new GoogleKeyCache(googleIamDAO, googleStorageDAO, pubSubDAO, googleServicesConfig, petServiceAccountConfig)
    val googleExt = new GoogleExtensions(directoryDAO, null, googleDirectoryDAO, null, googleIamDAO, null, null, cloudKeyCache, notificationDAO, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))

    val samRoutes = new TestSamRoutes(null, new UserService(directoryDAO, googleExt), new StatusService(directoryDAO, NoExtensions), null, UserInfo(OAuth2BearerToken(""), defaultUserId, defaultUserEmail, 0), directoryDAO) with GoogleExtensionRoutes {
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
    val googleStorageDAO = new MockGoogleStorageDAO()
    val notificationDAO = new PubSubNotificationDAO(pubSubDAO, "foo")
    val cloudKeyCache = new GoogleKeyCache(googleIamDAO, googleStorageDAO, pubSubDAO, googleServicesConfig, petServiceAccountConfig)
    val googleExt = new GoogleExtensions(directoryDAO, policyDAO, googleDirectoryDAO, pubSubDAO, googleIamDAO, null, null, cloudKeyCache, notificationDAO, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))

    // create a user
    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    Get(s"/api/google/user/proxyGroup/$defaultUserEmail") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response shouldBe defaultUserProxyEmail
    }
  }

  it should "return a user's proxy group from a pet service account" in withDefaultRoutes { samRoutes =>
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val googleIamDAO = new MockGoogleIamDAO()
    val policyDAO = new MockAccessPolicyDAO()
    val pubSubDAO = new MockGooglePubSubDAO()
    val googleStorageDAO = new MockGoogleStorageDAO()
    val notificationDAO = new PubSubNotificationDAO(pubSubDAO, "foo")
    val cloudKeyCache = new GoogleKeyCache(googleIamDAO, googleStorageDAO, pubSubDAO, googleServicesConfig, petServiceAccountConfig)
    val googleExt = new GoogleExtensions(directoryDAO, policyDAO, googleDirectoryDAO, pubSubDAO, googleIamDAO, null, null, cloudKeyCache, notificationDAO, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))

    // create a user
    Post("/register/user") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      responseAs[UserStatus] shouldEqual UserStatus(UserStatusDetails(defaultUserId, defaultUserEmail), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))
    }

    val petEmail = Get("/api/google/user/petServiceAccount/myproject") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith (s"@myproject.iam.gserviceaccount.com")
      response.value
    }

    Get(s"/api/google/user/proxyGroup/$petEmail") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response shouldBe defaultUserProxyEmail
    }
  }

  private object SamResourceActionPatterns {
    val readPolicies = ResourceActionPattern("read_policies", "", false)
    val alterPolicies = ResourceActionPattern("alter_policies", "", false)
    val delete = ResourceActionPattern("delete", "", false)

    val sharePolicy = ResourceActionPattern("share_policy::.+", "", false)
    val readPolicy = ResourceActionPattern("read_policy::.+", "", false)
  }

  private val name = ResourceType(ResourceTypeName("rt"), Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false), SamResourceActionPatterns.readPolicies), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("alter_policies"), ResourceAction("read_policies")))), ResourceRoleName("owner"))
  private val resourceType = ResourceType(ResourceTypeName("rt"), Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false), SamResourceActionPatterns.readPolicies), Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("alter_policies"), ResourceAction("read_policies")))), ResourceRoleName("owner"))

  "POST /api/google/policy/{resourceTypeName}/{resourceId}/{accessPolicyName}/sync" should "204 Create Google group for policy" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val defaultUserInfo = UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user123"), WorkbenchEmail("user1@example.com"), 0)
/* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
    val defaultUserProxyEmail = WorkbenchEmail(s"user1_user123@${googleServicesConfig.appsDomain}")
*/
    val defaultUserProxyEmail = WorkbenchEmail(s"PROXY_user123@${googleServicesConfig.appsDomain}")
/**/
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val googleIamDAO = new MockGoogleIamDAO()
    val googleStorageDAO = new MockGoogleStorageDAO()
    val policyDAO = new MockAccessPolicyDAO()
    val pubSubDAO = new MockGooglePubSubDAO()
    val notificationDAO = new PubSubNotificationDAO(pubSubDAO, "foo")
    val cloudKeyCache = new GoogleKeyCache(googleIamDAO, googleStorageDAO, pubSubDAO, googleServicesConfig, petServiceAccountConfig)
    val googleExt = new GoogleExtensions(directoryDAO, policyDAO, googleDirectoryDAO, pubSubDAO, googleIamDAO, googleStorageDAO, null, cloudKeyCache, notificationDAO, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))

    val emailDomain = "example.com"
    val mockResourceService = new ResourceService(resourceTypes, policyDAO, directoryDAO, googleExt, emailDomain)
    val mockUserService = new UserService(directoryDAO, googleExt)
    val mockStatusService = new StatusService(directoryDAO, NoExtensions)
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, resourceTypes, policyDAO, directoryDAO, googleExt, emailDomain)
    val samRoutes = new TestSamRoutes(mockResourceService, mockUserService, mockStatusService, mockManagedGroupService, UserInfo(OAuth2BearerToken(""), defaultUserInfo.userId, defaultUserInfo.userEmail, 0), directoryDAO) with GoogleExtensionRoutes {
      val googleExtensions = googleExt
      val googleKeyCache = cloudKeyCache
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
      assertResult(Map(createdPolicy.email -> Seq(SyncReportItem("added", defaultUserProxyEmail.value.toLowerCase, None)))) {
        responseAs[Map[WorkbenchEmail, Seq[SyncReportItem]]]
      }
    }
  }

  "GET /api/google/policy/{resourceTypeName}/{resourceId}/{accessPolicyName}/sync" should "200 with sync date and policy email" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val defaultUserInfo = UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val googleIamDAO = new MockGoogleIamDAO()
    val googleStorageDAO = new MockGoogleStorageDAO()
    val policyDAO = new MockAccessPolicyDAO()
    val pubSubDAO = new MockGooglePubSubDAO()
    val notificationDAO = new PubSubNotificationDAO(pubSubDAO, "foo")
    val cloudKeyCache = new GoogleKeyCache(googleIamDAO, googleStorageDAO, pubSubDAO, googleServicesConfig, petServiceAccountConfig)
    val googleExt = new GoogleExtensions(directoryDAO, policyDAO, googleDirectoryDAO, pubSubDAO, googleIamDAO, googleStorageDAO, null, cloudKeyCache, notificationDAO, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))

    val emailDomain = "example.com"
    val mockResourceService = new ResourceService(resourceTypes, policyDAO, directoryDAO, googleExt, emailDomain)
    val mockUserService = new UserService(directoryDAO, googleExt)
    val mockStatusService = new StatusService(directoryDAO, NoExtensions)
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, resourceTypes, policyDAO, directoryDAO, googleExt, emailDomain)
    val samRoutes = new TestSamRoutes(mockResourceService, mockUserService, mockStatusService, mockManagedGroupService, UserInfo(OAuth2BearerToken(""), defaultUserInfo.userId, defaultUserInfo.userEmail, 0), directoryDAO) with GoogleExtensionRoutes {
      val googleExtensions = googleExt
      val googleKeyCache = cloudKeyCache
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

    runAndWait(directoryDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(resourceType.ownerRoleName.value + ".foo." + resourceType.name), Set.empty, WorkbenchEmail("foo@bar.com"))))

    Get(s"/api/google/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] should not be empty
    }
  }

  "GET /api/google/user/petServiceAccount/{project}/key" should "200 with a new key" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val defaultUserInfo = UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val (googleIamDAO: GoogleIamDAO, expectedJson: String) = createMockGoogleIamDaoForSAKeyTests
    val googleStorageDAO = new MockGoogleStorageDAO()
    val policyDAO = new MockAccessPolicyDAO()
    val pubSubDAO = new MockGooglePubSubDAO()
    val notificationDAO = new PubSubNotificationDAO(pubSubDAO, "foo")
    val cloudKeyCache = new GoogleKeyCache(googleIamDAO, googleStorageDAO, pubSubDAO, googleServicesConfig, petServiceAccountConfig)
    val googleExt = new GoogleExtensions(directoryDAO, policyDAO, googleDirectoryDAO, pubSubDAO, googleIamDAO, googleStorageDAO, null, cloudKeyCache, notificationDAO, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))

    val emailDomain = "example.com"
    val mockResourceService = new ResourceService(resourceTypes, policyDAO, directoryDAO, googleExt, emailDomain)
    val mockUserService = new UserService(directoryDAO, googleExt)
    val mockStatusService = new StatusService(directoryDAO, NoExtensions)
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, resourceTypes, policyDAO, directoryDAO, googleExt, emailDomain)
    val samRoutes = new TestSamRoutes(mockResourceService, mockUserService, mockStatusService, mockManagedGroupService, UserInfo(OAuth2BearerToken(""), defaultUserInfo.userId, defaultUserInfo.userEmail, 0), directoryDAO) with GoogleExtensionRoutes {
      val googleExtensions = googleExt
      val googleKeyCache = cloudKeyCache
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
      val response = responseAs[String]
      response shouldEqual(expectedJson)
    }
  }

  "DELETE /api/google/user/petServiceAccount/{project}/key/{keyId}" should "204 when deleting a key" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val defaultUserInfo = UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val (googleIamDAO: GoogleIamDAO, expectedJson: String) = createMockGoogleIamDaoForSAKeyTests
    val googleStorageDAO = new MockGoogleStorageDAO()
    val policyDAO = new MockAccessPolicyDAO()
    val pubSubDAO = new MockGooglePubSubDAO()
    val notificationDAO = new PubSubNotificationDAO(pubSubDAO, "foo")
    val cloudKeyCache = new GoogleKeyCache(googleIamDAO, googleStorageDAO, pubSubDAO, googleServicesConfig, petServiceAccountConfig)
    val googleExt = new GoogleExtensions(directoryDAO, policyDAO, googleDirectoryDAO, pubSubDAO, googleIamDAO, googleStorageDAO, null, cloudKeyCache, notificationDAO, googleServicesConfig, petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))

    val emailDomain = "example.com"
    val mockResourceService = new ResourceService(resourceTypes, policyDAO, directoryDAO, googleExt, emailDomain)
    val mockUserService = new UserService(directoryDAO, googleExt)
    val mockStatusService = new StatusService(directoryDAO, NoExtensions)
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, resourceTypes, policyDAO, directoryDAO, googleExt, emailDomain)
    val samRoutes = new TestSamRoutes(mockResourceService, mockUserService, mockStatusService, mockManagedGroupService, UserInfo(OAuth2BearerToken(""), defaultUserInfo.userId, defaultUserInfo.userEmail, 0), directoryDAO) with GoogleExtensionRoutes {
      val googleExtensions = googleExt
      val googleKeyCache = cloudKeyCache
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
      val response = responseAs[String]
      response shouldEqual(expectedJson)
    }

    // create a pet service account key
    Delete("/api/google/user/petServiceAccount/myproject/key/123") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  private def createMockGoogleIamDaoForSAKeyTests: (GoogleIamDAO, String) = {
    val googleIamDAO = mock[GoogleIamDAO]
    val expectedJson = """{"json":"yes I am"}"""
    when(googleIamDAO.findServiceAccount(any[GoogleProject], any[ServiceAccountName])).thenReturn(Future.successful(None))
    when(googleIamDAO.createServiceAccount(any[GoogleProject], any[ServiceAccountName], any[ServiceAccountDisplayName])).thenReturn(Future.successful(ServiceAccount(ServiceAccountSubjectId("12312341234"), WorkbenchEmail("pet@myproject.iam.gserviceaccount.com"), ServiceAccountDisplayName(""))))
    when(googleIamDAO.createServiceAccountKey(any[GoogleProject], any[WorkbenchEmail])).thenReturn(Future.successful(ServiceAccountKey(ServiceAccountKeyId("foo"), ServiceAccountPrivateKeyData(ServiceAccountPrivateKeyData(expectedJson).encode), None, None)))
    when(googleIamDAO.removeServiceAccountKey(any[GoogleProject], any[WorkbenchEmail], any[ServiceAccountKeyId])).thenReturn(Future.successful(()))
    when(googleIamDAO.listUserManagedServiceAccountKeys(any[GoogleProject], any[WorkbenchEmail])).thenReturn(Future.successful(Seq.empty))
    (googleIamDAO, expectedJson)
  }

  private def setupPetSATest: (UserInfo, TestSamRoutes with GoogleExtensionRoutes, String) = {
    val defaultUserInfo = UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val (googleIamDAO: GoogleIamDAO, expectedJson: String) = createMockGoogleIamDaoForSAKeyTests
    val googleStorageDAO = new MockGoogleStorageDAO
    val policyDAO = new MockAccessPolicyDAO()
    val pubSubDAO = new MockGooglePubSubDAO()
    val notificationDAO = new PubSubNotificationDAO(pubSubDAO, "foo")
    val cloudKeyCache = new GoogleKeyCache(googleIamDAO, googleStorageDAO, pubSubDAO, googleServicesConfig, petServiceAccountConfig)
    val googleExt = new GoogleExtensions(directoryDAO, policyDAO, googleDirectoryDAO, pubSubDAO, googleIamDAO, googleStorageDAO, null, cloudKeyCache, notificationDAO, googleServicesConfig.copy(serviceAccountClientEmail = defaultUserInfo.userEmail, serviceAccountClientId = defaultUserInfo.userId.value), petServiceAccountConfig, configResourceTypes(CloudExtensions.resourceTypeName))

    val emailDomain = "example.com"
    val mockResourceService = new ResourceService(configResourceTypes, policyDAO, directoryDAO, googleExt, emailDomain)
    val userService = new UserService(directoryDAO, googleExt)
    val statusService = new StatusService(directoryDAO, NoExtensions)
    val managedGroupService = new ManagedGroupService(mockResourceService, configResourceTypes, policyDAO, directoryDAO, googleExt, emailDomain)
    val samRoutes = new TestSamRoutes(mockResourceService, userService, statusService, managedGroupService, UserInfo(OAuth2BearerToken(""), defaultUserInfo.userId, defaultUserInfo.userEmail, 0), directoryDAO) with GoogleExtensionRoutes {
      val googleExtensions = googleExt
      val googleKeyCache = cloudKeyCache
    }

    runAndWait(googleExt.onBoot(SamApplication(userService, mockResourceService, statusService)))
    (defaultUserInfo, samRoutes, expectedJson)
  }

  "GET /api/google/petServiceAccount/{project}/{userEmail}" should "200 with a key" in {
    val (defaultUserInfo, samRoutes, expectedJson) = setupPetSATest

    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(GoogleExtensions.getPetPrivateKeyAction), Set.empty)
    Put(s"/api/resource/${CloudExtensions.resourceTypeName.value}/${GoogleExtensions.resourceId.value}/policies/foo", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    // create a pet service account key
    Get(s"/api/google/petServiceAccount/myproject/${defaultUserInfo.userEmail.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[String]
      response shouldEqual(expectedJson)
    }
  }

  it should "404 when user does not exist" in {
    val (defaultUserInfo, samRoutes, _) = setupPetSATest

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
    val (_, samRoutes, _) = setupPetSATest

    // create a pet service account key
    Get(s"/api/google/petServiceAccount/myproject/I-do-not-exist@foo.bar") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }
}
