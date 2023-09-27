package org.broadinstitute.dsde.workbench.sam
package google

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.RouteTestTimeout
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api._
import org.broadinstitute.dsde.workbench.sam.service._
import org.scalatest.concurrent.ScalaFutures

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.concurrent.duration._

/** Unit tests of GoogleExtensionRoutes. Can use real Google services. Must mock everything else.
  */
class GoogleExtensionRoutesV1Spec extends GoogleExtensionRoutesSpecHelper with ScalaFutures {
  implicit val timeout = RouteTestTimeout(
    5 seconds
  ) // after using com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper, tests seems to run a bit longer

  "GET /api/google/v1/user/petServiceAccount" should "get or create a pet service account for a user" in {
    assume(databaseEnabled, databaseEnabledClue)

    val (user, _, routes) = createTestUser()

    // create a pet service account
    Get("/api/google/v1/user/petServiceAccount/myproject") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith(s"@myproject.iam.gserviceaccount.com")
    }

    // same result a second time
    Get("/api/google/v1/user/petServiceAccount/myproject") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith(s"@myproject.iam.gserviceaccount.com")
    }
  }

  "GET /api/google/v1/user/proxyGroup/{email}" should "return a user's proxy group" in {
    val (user, _, routes) = createTestUser()
    Get(s"/api/google/v1/user/proxyGroup/${user.email}") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response shouldBe WorkbenchEmail(s"PROXY_${user.id}@${googleServicesConfig.appsDomain}")
    }
  }

  it should "return a user's proxy group from a pet service account" in {
    assume(databaseEnabled, databaseEnabledClue)

    val (user, _, routes) = createTestUser()

    val petEmail = Get("/api/google/v1/user/petServiceAccount/myproject") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith(s"@myproject.iam.gserviceaccount.com")
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

  private val name = ResourceType(
    ResourceTypeName("rt"),
    Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false), SamResourceActionPatterns.readPolicies),
    Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("alter_policies"), ResourceAction("read_policies")))),
    ResourceRoleName("owner")
  )
  private val resourceType = ResourceType(
    ResourceTypeName("rt"),
    Set(SamResourceActionPatterns.alterPolicies, ResourceActionPattern("can_compute", "", false), SamResourceActionPatterns.readPolicies),
    Set(ResourceRole(ResourceRoleName("owner"), Set(ResourceAction("alter_policies"), ResourceAction("read_policies")))),
    ResourceRoleName("owner")
  )

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
      responseAs[Seq[AccessPolicyResponseEntry]]
        .find(_.policyName == AccessPolicyName(resourceType.ownerRoleName.value))
        .getOrElse(fail("created policy not returned by get request"))
    }

    import SamGoogleModelJsonSupport._
    Post(s"/api/google/v1/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val proxyEmail = WorkbenchEmail(s"PROXY_${user.id}@${googleServicesConfig.appsDomain}")
      assertResult(
        Map(
          createdPolicy.email -> Seq(
            SyncReportItem("added", proxyEmail.value.toLowerCase, s"${createdPolicy.policyName.value}.foo.${resourceType.name.value}", None)
          )
        )
      ) {
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

    samDep.directoryDAO
      .createGroup(
        BasicWorkbenchGroup(WorkbenchGroupName(resourceType.ownerRoleName.value + ".foo." + resourceType.name), Set.empty, WorkbenchEmail("foo@bar.com")),
        samRequestContext = samRequestContext
      )
      .unsafeRunSync()

    Get(s"/api/google/v1/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] should not be empty
    }
  }

  "GET /api/google/v1/user/petServiceAccount/{project}/key" should "200 with a new key" in {
    assume(databaseEnabled, databaseEnabledClue)

    val resourceTypes = Map(resourceType.name -> resourceType)
    val (googleIamDAO, expectedJson: String) = createMockGoogleIamDaoForSAKeyTests

    val (user, _, routes) = createTestUser(resourceTypes, Some(googleIamDAO))

    // create a pet service account
    Get("/api/google/v1/user/petServiceAccount/myproject") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith(s"@myproject.iam.gserviceaccount.com")
    }

    // create a pet service account key
    Get("/api/google/v1/user/petServiceAccount/myproject/key") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[String]
      response shouldEqual expectedJson
    }
  }

  "DELETE /api/google/v1/user/petServiceAccount/{project}/key/{keyId}" should "204 when deleting a key" in {
    assume(databaseEnabled, databaseEnabledClue)

    val resourceTypes = Map(resourceType.name -> resourceType)
    val (googleIamDAO, expectedJson: String) = createMockGoogleIamDaoForSAKeyTests

    val (user, _, routes) = createTestUser(resourceTypes, Some(googleIamDAO))

    // create a pet service account
    Get("/api/google/v1/user/petServiceAccount/myproject") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith(s"@myproject.iam.gserviceaccount.com")
    }

    // create a pet service account key
    Get("/api/google/v1/user/petServiceAccount/myproject/key") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[String]
      response shouldEqual expectedJson
    }

    // create a pet service account key
    Delete("/api/google/v1/user/petServiceAccount/myproject/key/123") ~> routes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  "GET /api/google/v1/petServiceAccount/{project}/{userEmail}" should "200 with a key" in {
    assume(databaseEnabled, databaseEnabledClue)

    val (defaultUserInfo, samRoutes, expectedJson) = setupPetSATest()

    val members = AccessPolicyMembershipResponse(Set(defaultUserInfo.email), Set(GoogleExtensions.getPetPrivateKeyAction), Set.empty, None)
    Put(s"/api/resource/${CloudExtensions.resourceTypeName.value}/${GoogleExtensions.resourceId.value}/policies/foo", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    // create a pet service account key
    Get(s"/api/google/v1/petServiceAccount/myproject/${defaultUserInfo.email.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[String]
      response shouldEqual expectedJson
    }
  }

  it should "404 when user does not exist" in {
    val (defaultUserInfo, samRoutes, _) = setupPetSATest()

    val members = AccessPolicyMembershipResponse(Set(defaultUserInfo.email), Set(GoogleExtensions.getPetPrivateKeyAction), Set.empty, None)
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

  "POST /api/google/v1/user/petServiceAccount/{project}/signedUrlForBlob" should "200 with a signed url" in {
    val (user, samRoutes, projectName) = setupSignedUrlTest()
    val blob = SignedUrlRequest("my-bucket", "my-folder/my-object.txt")
    val urlEncodedEmail = URLEncoder.encode(user.email.value, StandardCharsets.UTF_8)

    Post(s"/api/google/v1/user/petServiceAccount/$projectName/signedUrlForBlob", blob) ~> samRoutes.route ~> check {
      responseAs[String] should include("my-bucket/my-folder/my-object.txt")
      responseAs[String] should include(s"userProject=$projectName")
      responseAs[String] should include(s"requestedBy=$urlEncodedEmail")
    }
  }

  it should "set a duration for a signed url" in {
    val (_, samRoutes, projectName) = setupSignedUrlTest()
    val blob = SignedUrlRequest("my-bucket", "my-folder/my-object.txt", Some(1))

    Post(s"/api/google/v1/user/petServiceAccount/$projectName/signedUrlForBlob", blob) ~> samRoutes.route ~> check {
      responseAs[String] should include("X-Goog-Expires=60")
    }
  }

  it should "skip requester pays" in {
    val (_, samRoutes, projectName) = setupSignedUrlTest()
    val blob = SignedUrlRequest("my-bucket", "my-folder/my-object.txt", Some(1), requesterPays = Option(false))

    Post(s"/api/google/v1/user/petServiceAccount/$projectName/signedUrlForBlob", blob) ~> samRoutes.route ~> check {
      responseAs[String] should not include "userProject"
    }
  }

  it should "404 when the user doesn't have access to the project" in {
    val (_, samRoutes, projectName) = setupSignedUrlTest()
    val blob = SignedUrlRequest("my-bucket", "my-folder/my-object.txt")
    Post(s"/api/google/v1/user/petServiceAccount/not-$projectName/signedUrlForBlob", blob) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }
  "POST /api/google/v1/user/signedUrlForBlob" should "200 with a signed url" in {
    val (user, samRoutes, projectName) = setupSignedUrlTest()
    val blob = RequesterPaysSignedUrlRequest("my-bucket", "my-folder/my-object.txt", requesterPaysProject = Some(projectName))
    val urlEncodedEmail = URLEncoder.encode(user.email.value, StandardCharsets.UTF_8)

    Post(s"/api/google/v1/user/signedUrlForBlob", blob) ~> samRoutes.route ~> check {
      responseAs[String] should include("my-bucket/my-folder/my-object.txt")
      responseAs[String] should include(s"userProject=$projectName")
      responseAs[String] should include(s"requestedBy=$urlEncodedEmail")
    }
  }

  it should "set a duration for a signed url" in {
    val (_, samRoutes, projectName) = setupSignedUrlTest()
    val blob = RequesterPaysSignedUrlRequest("my-bucket", "my-folder/my-object.txt", Some(2))

    Post(s"/api/google/v1/user/petServiceAccount/$projectName/signedUrlForBlob", blob) ~> samRoutes.route ~> check {
      responseAs[String] should include("X-Goog-Expires=120")
    }
  }

  it should "skip requester pays if no project provided" in {
    val (_, samRoutes, _) = setupSignedUrlTest()
    val blob = RequesterPaysSignedUrlRequest("my-bucket", "my-folder/my-object.txt", requesterPaysProject = None)

    Post(s"/api/google/v1/user/signedUrlForBlob", blob) ~> samRoutes.route ~> check {
      responseAs[String] should not include "userProject"
    }
  }
}
