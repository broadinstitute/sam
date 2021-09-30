package org.broadinstitute.dsde.workbench.sam
package google

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.RouteTestTimeout
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers.{any, eq => mockitoEq}
import org.mockito.Mockito._
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

/**
  * Unit tests of GoogleExtensionRoutes. Can use real Google services. Must mock everything else.
  */
class GoogleExtensionRoutesV1Spec extends GoogleExtensionRoutesSpecHelper with ScalaFutures with MockitoSugar {
  implicit val timeout = RouteTestTimeout(5 seconds) //after using com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper, tests seems to run a bit longer

  "GET /api/google/v1/user/petServiceAccount" should "get or create a pet service account for a user" in {
    val projectName = "myproject"

    val policyEvaluatorService = mock[PolicyEvaluatorService](RETURNS_SMART_NULLS)
    when(policyEvaluatorService.hasPermissionOneOf(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProject, ResourceId(projectName))), mockitoEq(Set(SamResourceActions.use_pet_service_account)), any[WorkbenchUserId], any[SamRequestContext]))
      .thenReturn(IO(true))

    val (user, _, routes) = createTestUser(policyEvaluatorServiceOpt = Option(policyEvaluatorService))

    // create a pet service account
    Get(s"/api/google/v1/user/petServiceAccount/$projectName") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith (s"@myproject.iam.gserviceaccount.com")
    }

    // same result a second time
    Get(s"/api/google/v1/user/petServiceAccount/$projectName") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith (s"@myproject.iam.gserviceaccount.com")
    }
  }

  it should "403 when the user doesn't have the right permission on the google-project resource" in {
    val projectName = "myproject"

    val policyEvaluatorService = mock[PolicyEvaluatorService](RETURNS_SMART_NULLS)
    when(policyEvaluatorService.hasPermissionOneOf(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProject, ResourceId(projectName))), mockitoEq(Set(SamResourceActions.use_pet_service_account)), any[WorkbenchUserId], any[SamRequestContext]))
      .thenReturn(IO(false))
    when(policyEvaluatorService.listUserResourceActions(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProject, ResourceId(projectName))), any[WorkbenchUserId], any[SamRequestContext]))
      .thenReturn(IO(Set(SamResourceActions.readPolicies)))

    val (_, _, routes) = createTestUser(policyEvaluatorServiceOpt = Option(policyEvaluatorService))

    // try to create a pet service account
    Get(s"/api/google/v1/user/petServiceAccount/$projectName") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when the user doesn't have any permission on the google-project resource" in {
    val projectName = "myproject"

    val policyEvaluatorService = mock[PolicyEvaluatorService](RETURNS_SMART_NULLS)
    when(policyEvaluatorService.hasPermissionOneOf(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProject, ResourceId(projectName))), mockitoEq(Set(SamResourceActions.use_pet_service_account)), any[WorkbenchUserId], any[SamRequestContext]))
      .thenReturn(IO(false))
    when(policyEvaluatorService.listUserResourceActions(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProject, ResourceId(projectName))), any[WorkbenchUserId], any[SamRequestContext]))
      .thenReturn(IO(Set.empty))

    val (_, _, routes) = createTestUser(policyEvaluatorServiceOpt = Option(policyEvaluatorService))

    // try to create a pet service account
    Get(s"/api/google/v1/user/petServiceAccount/$projectName") ~> routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/google/v1/user/proxyGroup/{email}" should "return a user's proxy group" in {
    val (user, _, routes) = createTestUser()
    Get(s"/api/google/v1/user/proxyGroup/$defaultUserEmail") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response shouldBe WorkbenchEmail(s"PROXY_${user.id}@${googleServicesConfig.appsDomain}")
    }
  }

  it should "return a user's proxy group from a pet service account" in {
    val projectName = "myproject"

    val policyEvaluatorService = mock[PolicyEvaluatorService](RETURNS_SMART_NULLS)
    when(policyEvaluatorService.hasPermissionOneOf(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProject, ResourceId(projectName))), mockitoEq(Set(SamResourceActions.use_pet_service_account)), any[WorkbenchUserId], any[SamRequestContext]))
      .thenReturn(IO(true))

    val (user, _, routes) = createTestUser(policyEvaluatorServiceOpt = Option(policyEvaluatorService))

    val petEmail = Get(s"/api/google/v1/user/petServiceAccount/$projectName") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith (s"@myproject.iam.gserviceaccount.com")
      response.value
    }

    Get(s"/api/google/v1/user/proxyGroup/$petEmail") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response shouldBe WorkbenchEmail(s"PROXY_${user.id.value}@${googleServicesConfig.appsDomain}")
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

  "POST /api/google/v1/policy/{resourceTypeName}/{resourceId}/{accessPolicyName}/sync" should "204 Create Google group for policy" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
//    val defaultUserInfo = UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user123"), WorkbenchEmail("user1@example.com"), 0)

    val (user, _, routes) = createTestUser(resourceTypes)

    Post(s"/api/resource/${resourceType.name}/foo") ~> routes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    import spray.json.DefaultJsonProtocol._
    val createdPolicy = Get(s"/api/resource/${resourceType.name}/foo/policies") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Seq[AccessPolicyResponseEntry]].find(_.policyName == AccessPolicyName(resourceType.ownerRoleName.value)).getOrElse(fail("created policy not returned by get request"))
    }

    import SamGoogleModelJsonSupport._
    Post(s"/api/google/v1/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val proxyEmail = WorkbenchEmail(s"PROXY_${user.id}@${googleServicesConfig.appsDomain}")
      assertResult(Map(createdPolicy.email -> Seq(SyncReportItem("added", proxyEmail.value.toLowerCase, None)))) {
        responseAs[Map[WorkbenchEmail, Seq[SyncReportItem]]]
      }
    }
  }

  "GET /api/google/v1/policy/{resourceTypeName}/{resourceId}/{accessPolicyName}/sync" should "200 with sync date and policy email" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val (user, samDep, routes) = createTestUser(resourceTypes)

    Post(s"/api/resource/${resourceType.name}/foo") ~> routes.route ~> check {
      status shouldEqual StatusCodes.NoContent
      assertResult("") {
        responseAs[String]
      }
    }

    Get(s"/api/google/v1/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync") ~> routes.route ~> check {
      status shouldEqual StatusCodes.NoContent
      assertResult("") {
        responseAs[String]
      }
    }

    Post(s"/api/google/v1/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    samDep.directoryDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(resourceType.ownerRoleName.value + ".foo." + resourceType.name), Set.empty, WorkbenchEmail("foo@bar.com")), samRequestContext = samRequestContext).unsafeRunSync()

    Get(s"/api/google/v1/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] should not be empty
    }
  }

  "GET /api/google/v1/user/petServiceAccount/{project}/key" should "200 with a new key" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val (googleIamDAO, expectedJson: String) = createMockGoogleIamDaoForSAKeyTests
    val projectName = "myproject"

    val policyEvaluatorService = mock[PolicyEvaluatorService](RETURNS_SMART_NULLS)
    when(policyEvaluatorService.hasPermissionOneOf(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProject, ResourceId(projectName))), mockitoEq(Set(SamResourceActions.use_pet_service_account)), any[WorkbenchUserId], any[SamRequestContext]))
      .thenReturn(IO(true))

    val (user, _, routes) = createTestUser(resourceTypes, Some(googleIamDAO), policyEvaluatorServiceOpt = Option(policyEvaluatorService))

    // create a pet service account
    Get(s"/api/google/v1/user/petServiceAccount/$projectName") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith (s"@myproject.iam.gserviceaccount.com")
    }

    // create a pet service account key
    Get(s"/api/google/v1/user/petServiceAccount/$projectName/key") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[String]
      response shouldEqual(expectedJson)
    }
  }

  it should "403 when the user doesn't have the right permission on the google-project resource" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val (googleIamDAO, _) = createMockGoogleIamDaoForSAKeyTests
    val projectName = "myproject"

    val policyEvaluatorService = mock[PolicyEvaluatorService](RETURNS_SMART_NULLS)
    when(policyEvaluatorService.hasPermissionOneOf(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProject, ResourceId(projectName))), mockitoEq(Set(SamResourceActions.use_pet_service_account)), any[WorkbenchUserId], any[SamRequestContext]))
      .thenReturn(IO(false))
    when(policyEvaluatorService.listUserResourceActions(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProject, ResourceId(projectName))), any[WorkbenchUserId], any[SamRequestContext]))
      .thenReturn(IO(Set(SamResourceActions.readPolicies)))

    val (_, _, routes) = createTestUser(resourceTypes, Some(googleIamDAO), policyEvaluatorServiceOpt = Option(policyEvaluatorService))

    // try to create a pet service account key
    Get(s"/api/google/v1/user/petServiceAccount/$projectName/key") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when the user doesn't have any permission on the google-project resource" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val (googleIamDAO, _) = createMockGoogleIamDaoForSAKeyTests
    val projectName = "myproject"

    val policyEvaluatorService = mock[PolicyEvaluatorService](RETURNS_SMART_NULLS)
    when(policyEvaluatorService.hasPermissionOneOf(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProject, ResourceId(projectName))), mockitoEq(Set(SamResourceActions.use_pet_service_account)), any[WorkbenchUserId], any[SamRequestContext]))
      .thenReturn(IO(false))
    when(policyEvaluatorService.listUserResourceActions(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProject, ResourceId(projectName))), any[WorkbenchUserId], any[SamRequestContext]))
      .thenReturn(IO(Set.empty))

    val (_, _, routes) = createTestUser(resourceTypes, Some(googleIamDAO), policyEvaluatorServiceOpt = Option(policyEvaluatorService))

    // try to create a pet service account key
    Get(s"/api/google/v1/user/petServiceAccount/$projectName/key") ~> routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "DELETE /api/google/v1/user/petServiceAccount/{project}/key/{keyId}" should "204 when deleting a key" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val (googleIamDAO, expectedJson: String) = createMockGoogleIamDaoForSAKeyTests
    val projectName = "myproject"

    val policyEvaluatorService = mock[PolicyEvaluatorService](RETURNS_SMART_NULLS)
    when(policyEvaluatorService.hasPermissionOneOf(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProject, ResourceId(projectName))), mockitoEq(Set(SamResourceActions.use_pet_service_account)), any[WorkbenchUserId], any[SamRequestContext]))
      .thenReturn(IO(true))

    val (user, _, routes) = createTestUser(resourceTypes, Some(googleIamDAO), policyEvaluatorServiceOpt = Option(policyEvaluatorService))

    // create a pet service account
    Get(s"/api/google/v1/user/petServiceAccount/$projectName") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith (s"@myproject.iam.gserviceaccount.com")
    }

    // create a pet service account key
    Get(s"/api/google/v1/user/petServiceAccount/$projectName/key") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[String]
      response shouldEqual(expectedJson)
    }

    // create a pet service account key
    Delete(s"/api/google/v1/user/petServiceAccount/$projectName/key/123") ~> routes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  "GET /api/google/v1/petServiceAccount/{project}/{userEmail}" should "200 with a key" in {
    val (defaultUserInfo, samRoutes, expectedJson) = setupPetSATest()

    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(GoogleExtensions.getPetPrivateKeyAction), Set.empty, None)
    Put(s"/api/resource/${CloudExtensions.resourceTypeName.value}/${GoogleExtensions.resourceId.value}/policies/foo", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    // create a pet service account key
    Get(s"/api/google/v1/petServiceAccount/myproject/${defaultUserInfo.userEmail.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[String]
      response shouldEqual(expectedJson)
    }
  }

  it should "404 when user does not exist" in {
    val (defaultUserInfo, samRoutes, _) = setupPetSATest()

    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(GoogleExtensions.getPetPrivateKeyAction), Set.empty, None)
    Put(s"/api/resource/${CloudExtensions.resourceTypeName.value}/${GoogleExtensions.resourceId.value}/policies/foo", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    // create a pet service account key
    Get(s"/api/google/v1/petServiceAccount/myproject/I-do-not-exist@foo.bar") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "403 when caller does not have action" in {
    val (_, samRoutes, _) = setupPetSATest()

    // create a pet service account key
    Get(s"/api/google/v1/petServiceAccount/myproject/I-do-not-exist@foo.bar") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }
}
