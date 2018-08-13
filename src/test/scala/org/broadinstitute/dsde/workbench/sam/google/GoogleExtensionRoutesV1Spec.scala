package org.broadinstitute.dsde.workbench.sam
package google

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.scalatest.concurrent.ScalaFutures

/**
  * Unit tests of GoogleExtensionRoutes. Can use real Google services. Must mock everything else.
  */
class GoogleExtensionRoutesV1Spec extends GoogleExtensionRoutesSpecHelper with ScalaFutures{
  "GET /api/google/v1/user/petServiceAccount" should "get or create a pet service account for a user" in {
    val (user, headers, _, routes) = createTestUser()

    // create a pet service account
    Get("/api/google/v1/user/petServiceAccount/myproject").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith (s"@myproject.iam.gserviceaccount.com")
    }

    // same result a second time
    Get("/api/google/v1/user/petServiceAccount/myproject").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith (s"@myproject.iam.gserviceaccount.com")
    }
  }

  "GET /api/google/v1/user/proxyGroup/{email}" should "return a user's proxy group" in {
    val (user, headers, _, routes) = createTestUser()
    Get(s"/api/google/v1/user/proxyGroup/$defaultUserEmail").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response shouldBe WorkbenchEmail(s"PROXY_${user.id}@${googleServicesConfig.appsDomain}")
    }
  }

  it should "return a user's proxy group from a pet service account" in {
    val (user, headers, _, routes) = createTestUser()

    val petEmail = Get("/api/google/v1/user/petServiceAccount/myproject").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith (s"@myproject.iam.gserviceaccount.com")
      response.value
    }

    Get(s"/api/google/v1/user/proxyGroup/$petEmail").withHeaders(headers) ~> routes.route ~> check {
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
/* Re-enable this code and remove the temporary code below after fixing rawls for GAWB-2933
    val defaultUserProxyEmail = WorkbenchEmail(s"user1_user123@${googleServicesConfig.appsDomain}")
*/
/**/

    val (user, headers, _, routes) = createTestUser(resourceTypes)

    Post(s"/api/resource/${resourceType.name}/foo").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }

    import spray.json.DefaultJsonProtocol._
    val createdPolicy = Get(s"/api/resource/${resourceType.name}/foo/policies").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Seq[AccessPolicyResponseEntry]].find(_.policyName == AccessPolicyName(resourceType.ownerRoleName.value)).getOrElse(fail("created policy not returned by get request"))
    }

    import GoogleModelJsonSupport._
    Post(s"/api/google/v1/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val proxyEmail = WorkbenchEmail(s"PROXY_${user.id}@${googleServicesConfig.appsDomain}")
      assertResult(Map(createdPolicy.email -> Seq(SyncReportItem("added", proxyEmail.value.toLowerCase, None)))) {
        responseAs[Map[WorkbenchEmail, Seq[SyncReportItem]]]
      }
    }
  }

  "GET /api/google/v1/policy/{resourceTypeName}/{resourceId}/{accessPolicyName}/sync" should "200 with sync date and policy email" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val (user, headers, samDep, routes) = createTestUser(resourceTypes)

    Post(s"/api/resource/${resourceType.name}/foo").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.NoContent
      assertResult("") {
        responseAs[String]
      }
    }

    Get(s"/api/google/v1/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.NoContent
      assertResult("") {
        responseAs[String]
      }
    }

    Post(s"/api/google/v1/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    samDep.directoryDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(resourceType.ownerRoleName.value + ".foo." + resourceType.name), Set.empty, WorkbenchEmail("foo@bar.com"))).futureValue

    Get(s"/api/google/v1/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] should not be empty
    }
  }

  "GET /api/google/v1/user/petServiceAccount/{project}/key" should "200 with a new key" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val (googleIamDAO, expectedJson: String) = createMockGoogleIamDaoForSAKeyTests

    val (user, headers, _, routes) = createTestUser(resourceTypes, Some(googleIamDAO))

    // create a pet service account
    Get("/api/google/v1/user/petServiceAccount/myproject").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith (s"@myproject.iam.gserviceaccount.com")
    }

    // create a pet service account key
    Get("/api/google/v1/user/petServiceAccount/myproject/key").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[String]
      response shouldEqual(expectedJson)
    }
  }


  "DELETE /api/google/v1/user/petServiceAccount/{project}/key/{keyId}" should "204 when deleting a key" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val (googleIamDAO, expectedJson: String) = createMockGoogleIamDaoForSAKeyTests

    val (user, headers, _, routes) = createTestUser(resourceTypes, Some(googleIamDAO))

    // create a pet service account
    Get("/api/google/v1/user/petServiceAccount/myproject").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith (s"@myproject.iam.gserviceaccount.com")
    }

    // create a pet service account key
    Get("/api/google/v1/user/petServiceAccount/myproject/key").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[String]
      response shouldEqual(expectedJson)
    }

    // create a pet service account key
    Delete("/api/google/v1/user/petServiceAccount/myproject/key/123").withHeaders(headers) ~> routes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  "GET /api/google/v1/petServiceAccount/{project}/{userEmail}" should "200 with a key" in {
    val (defaultUserInfo, samRoutes, expectedJson, headers) = setupPetSATest()

    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(GoogleExtensions.getPetPrivateKeyAction), Set.empty)
    Put(s"/api/resource/${CloudExtensions.resourceTypeName.value}/${GoogleExtensions.resourceId.value}/policies/foo", members).withHeaders(headers) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    // create a pet service account key
    Get(s"/api/google/v1/petServiceAccount/myproject/${defaultUserInfo.userEmail.value}").withHeaders(headers) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[String]
      response shouldEqual(expectedJson)
    }
  }

  it should "404 when user does not exist" in {
    val (defaultUserInfo, samRoutes, _, headers) = setupPetSATest()

    val members = AccessPolicyMembership(Set(defaultUserInfo.userEmail), Set(GoogleExtensions.getPetPrivateKeyAction), Set.empty)
    Put(s"/api/resource/${CloudExtensions.resourceTypeName.value}/${GoogleExtensions.resourceId.value}/policies/foo", members).withHeaders(headers) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    // create a pet service account key
    Get(s"/api/google/v1/petServiceAccount/myproject/I-do-not-exist@foo.bar").withHeaders(headers) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "403 when caller does not have action" in {
    val (_, samRoutes, _, headers) = setupPetSATest()

    // create a pet service account key
    Get(s"/api/google/v1/petServiceAccount/myproject/I-do-not-exist@foo.bar").withHeaders(headers) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }
}
