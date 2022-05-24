package org.broadinstitute.dsde.workbench.sam
package google

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.google.GoogleIamDAO
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.sam.Generator._
import org.broadinstitute.dsde.workbench.sam.TestSupport.{genSamDependencies, genSamRoutes, _}
import org.broadinstitute.dsde.workbench.sam.api.SamRoutes
import org.broadinstitute.dsde.workbench.sam.config.GoogleServicesConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.MockRegistrationDAO
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers.{any, eq => mockitoEq}
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Future
import scala.concurrent.duration._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * Unit tests of GoogleExtensionRoutes. Can use real Google services. Must mock everything else.
  */
class GoogleExtensionRoutesSpec extends GoogleExtensionRoutesSpecHelper with ScalaFutures{
  implicit val timeout = RouteTestTimeout(5 seconds) //after using com.google.cloud.storage.contrib.nio.testing.LocalStorageHelper, tests seems to run a bit longer
  val workspaceResourceId = "workspace"
  val v2GoogleProjectResourceId = "v2project"

  private def getPetServiceAccount(projectName: String, routes: SamRoutes) = {
    Get(s"/api/google/user/petServiceAccount/${projectName}") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response.value should endWith (s"@${projectName}.iam.gserviceaccount.com")
      response.value
    }
  }

  private def getPetServiceAccountKey(projectName: String, expectedJson: String, routes: SamRoutes) = {
    Get(s"/api/google/user/petServiceAccount/${projectName}/key") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[String]
      response shouldEqual(expectedJson)
    }
  }

  "GET /api/google/user/petServiceAccount" should "get or create a pet service account for a user in a v2 project" in {
    val policyEvaluatorService = mock[PolicyEvaluatorService](RETURNS_SMART_NULLS)
    val resourceService = mock[ResourceService](RETURNS_SMART_NULLS)

    when(resourceService.getResourceParent(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProjectName, ResourceId(v2GoogleProjectResourceId))), any[SamRequestContext]))
      .thenReturn(IO(Option(FullyQualifiedResourceId(SamResourceTypes.workspaceName, ResourceId(workspaceResourceId)))))
    when(policyEvaluatorService.hasPermissionOneOf(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProjectName, ResourceId(v2GoogleProjectResourceId))), mockitoEq(Set(SamResourceActions.createPet)), any[WorkbenchUserId], any[SamRequestContext]))
      .thenReturn(IO(true))
    val (_, _, routes) = createTestUser(policyEvaluatorServiceOpt = Option(policyEvaluatorService), resourceServiceOpt = Option(resourceService))

    // create a pet service account
    getPetServiceAccount(v2GoogleProjectResourceId, routes)

    // same result a second time
    getPetServiceAccount(v2GoogleProjectResourceId, routes)
  }

  it should "200 when the user doesn't have the right permission on the google-project resource, but it is v1" in {
    val projectName = "myproject"

    val (_, _, routes) = createTestUser()

    // try to create a pet service account
    getPetServiceAccount(projectName, routes)
  }

  it should "403 when the user doesn't have the right permission on the google-project resource" in {
    val policyEvaluatorService = mock[PolicyEvaluatorService](RETURNS_SMART_NULLS)
    val resourceService = mock[ResourceService](RETURNS_SMART_NULLS)

    when(resourceService.getResourceParent(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProjectName, ResourceId(v2GoogleProjectResourceId))), any[SamRequestContext]))
      .thenReturn(IO(Option(FullyQualifiedResourceId(SamResourceTypes.workspaceName, ResourceId(workspaceResourceId)))))
    when(policyEvaluatorService.hasPermissionOneOf(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProjectName, ResourceId(v2GoogleProjectResourceId))), mockitoEq(Set(SamResourceActions.createPet)), any[WorkbenchUserId], any[SamRequestContext]))
      .thenReturn(IO(false))
    when(policyEvaluatorService.listUserResourceActions(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProjectName, ResourceId(v2GoogleProjectResourceId))), any[WorkbenchUserId], any[SamRequestContext]))
      .thenReturn(IO(Set(SamResourceActions.readPolicies)))

    val (_, _, routes) = createTestUser(policyEvaluatorServiceOpt = Option(policyEvaluatorService), resourceServiceOpt = Option(resourceService))

    // try to create a pet service account
    Get(s"/api/google/user/petServiceAccount/$v2GoogleProjectResourceId") ~> routes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 when the user doesn't have any permission on the google-project resource" in {


    val policyEvaluatorService = mock[PolicyEvaluatorService](RETURNS_SMART_NULLS)
    val resourceService = mock[ResourceService](RETURNS_SMART_NULLS)

    when(resourceService.getResourceParent(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProjectName, ResourceId(v2GoogleProjectResourceId))), any[SamRequestContext]))
      .thenReturn(IO(Option(FullyQualifiedResourceId(SamResourceTypes.workspaceName, ResourceId(workspaceResourceId)))))
    when(policyEvaluatorService.hasPermissionOneOf(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProjectName, ResourceId(v2GoogleProjectResourceId))), mockitoEq(Set(SamResourceActions.createPet)), any[WorkbenchUserId], any[SamRequestContext]))
      .thenReturn(IO(false))
    when(policyEvaluatorService.listUserResourceActions(mockitoEq(FullyQualifiedResourceId(SamResourceTypes.googleProjectName, ResourceId(v2GoogleProjectResourceId))), any[WorkbenchUserId], any[SamRequestContext]))
      .thenReturn(IO(Set.empty))

    val (_, _, routes) = createTestUser(policyEvaluatorServiceOpt = Option(policyEvaluatorService), resourceServiceOpt = Option(resourceService))

    // try to create a pet service account
    Get(s"/api/google/user/petServiceAccount/$v2GoogleProjectResourceId") ~> routes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "GET /api/google/user/proxyGroup/{email}" should "return a user's proxy group" in {
    val (user, _, routes) = createTestUser()

    Get(s"/api/google/user/proxyGroup/${user.email}") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response shouldBe TestSupport.proxyEmail(user.id)
    }
  }

  it should "return a user's proxy group from a pet service account" in {
    val (user, _, routes) = createTestUser()


    val petEmail = getPetServiceAccount("myproject", routes)

    Get(s"/api/google/user/proxyGroup/$petEmail") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[WorkbenchEmail]
      response shouldBe TestSupport.proxyEmail(user.id)
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
    Post(s"/api/google/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      assertResult(Map(createdPolicy.email -> Seq(SyncReportItem("added", TestSupport.proxyEmail(user.id).value.toLowerCase, None)))) {
        responseAs[Map[WorkbenchEmail, Seq[SyncReportItem]]]
      }
    }
  }

  "GET /api/google/policy/{resourceTypeName}/{resourceId}/{accessPolicyName}/sync" should "200 with sync date and policy email" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val (user, samDep, routes) = createTestUser(resourceTypes)

    Post(s"/api/resource/${resourceType.name}/foo") ~> routes.route ~> check {
      status shouldEqual StatusCodes.NoContent
      assertResult("") {
        responseAs[String]
      }
    }

    Get(s"/api/google/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync") ~> routes.route ~> check {
      status shouldEqual StatusCodes.NoContent
      assertResult("") {
        responseAs[String]
      }
    }

    Post(s"/api/google/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
    }

    samDep.directoryDAO.createGroup(BasicWorkbenchGroup(WorkbenchGroupName(resourceType.ownerRoleName.value + ".foo." + resourceType.name), Set.empty, WorkbenchEmail("foo@bar.com")), samRequestContext = samRequestContext).unsafeRunSync()

    Get(s"/api/google/resource/${resourceType.name}/foo/${resourceType.ownerRoleName.value}/sync") ~> routes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[String] should not be empty
    }
  }

  "GET /api/google/user/petServiceAccount/{project}/key" should "200 with a new key" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val (googleIamDAO, expectedJson: String) = createMockGoogleIamDaoForSAKeyTests

    val (user, _, routes) = createTestUser(resourceTypes, Some(googleIamDAO))

    // create a pet service account
    getPetServiceAccount("myproject", routes)

    // create a pet service account key
    getPetServiceAccountKey("myproject", expectedJson, routes)
  }

  "DELETE /api/google/user/petServiceAccount/{project}/key/{keyId}" should "204 when deleting a key" in {
    val resourceTypes = Map(resourceType.name -> resourceType)
    val (googleIamDAO, expectedJson: String) = createMockGoogleIamDaoForSAKeyTests

    val (user, _, routes) = createTestUser(resourceTypes, Some(googleIamDAO))

    // create a pet service account
    getPetServiceAccount("myproject", routes)

    // create a pet service account key
    getPetServiceAccountKey("myproject", expectedJson, routes)

    // create a pet service account key
    Delete("/api/google/user/petServiceAccount/myproject/key/123") ~> routes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  "GET /api/google/petServiceAccount/{project}/{userEmail}" should "200 with a key" in {
    val (defaultUserInfo, samRoutes, expectedJson) = setupPetSATest()

    val members = AccessPolicyMembership(Set(defaultUserInfo.email), Set(GoogleExtensions.getPetPrivateKeyAction), Set.empty, None)
    Put(s"/api/resource/${CloudExtensions.resourceTypeName.value}/${GoogleExtensions.resourceId.value}/policies/foo", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    // create a pet service account key
    Get(s"/api/google/petServiceAccount/myproject/${defaultUserInfo.email.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[String]
      response shouldEqual(expectedJson)
    }
  }

  it should "404 when user does not exist" in {
    val (defaultUserInfo, samRoutes, _) = setupPetSATest()

    val members = AccessPolicyMembership(Set(defaultUserInfo.email), Set(GoogleExtensions.getPetPrivateKeyAction), Set.empty, None)
    Put(s"/api/resource/${CloudExtensions.resourceTypeName.value}/${GoogleExtensions.resourceId.value}/policies/foo", members) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    // create a pet service account key
    Get(s"/api/google/petServiceAccount/myproject/I-do-not-exist@foo.bar") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "403 when caller does not have action" in {
    val (_, samRoutes, _) = setupPetSATest()

    // create a pet service account key
    Get(s"/api/google/petServiceAccount/myproject/I-do-not-exist@foo.bar") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }
}

trait GoogleExtensionRoutesSpecHelper extends AnyFlatSpec with Matchers with ScalatestRouteTest with TestSupport with MockitoSugar{
  val defaultUserId = genWorkbenchUserId.sample.get
  val defaultUserEmail = genNonPetEmail.sample.get
  val defaultUserProxyEmail = WorkbenchEmail(s"PROXY_$defaultUserId@${googleServicesConfig.appsSubdomain}")

  val configResourceTypes = TestSupport.configResourceTypes

  def createTestUser(resourceTypes: Map[ResourceTypeName, ResourceType] = Map.empty[ResourceTypeName, ResourceType],
                     googIamDAO: Option[GoogleIamDAO] = None,
                     googleServicesConfig: GoogleServicesConfig = TestSupport.googleServicesConfig,
                     policyEvaluatorServiceOpt: Option[PolicyEvaluatorService] = None,
                     resourceServiceOpt: Option[ResourceService] = None,
                     user: SamUser = Generator.genWorkbenchUserBoth.sample.get
                    ): (SamUser, SamDependencies, SamRoutes) = {
    val samDependencies = genSamDependencies(resourceTypes, googIamDAO, googleServicesConfig, policyEvaluatorServiceOpt = policyEvaluatorServiceOpt, resourceServiceOpt = resourceServiceOpt)
    val createRoutes = genSamRoutes(samDependencies, user)

    // create a user
    Post("/register/user/v1/") ~> createRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
      val res = responseAs[UserStatus]
      res.userInfo.userEmail shouldBe user.email
      res.enabled shouldBe Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true)
    }

    (user, samDependencies, createRoutes)
  }

  def createMockGoogleIamDaoForSAKeyTests: (GoogleIamDAO, String) = {
    val googleIamDAO = mock[GoogleIamDAO](RETURNS_SMART_NULLS)
    val expectedJson = """{"json":"yes I am"}"""
    when(googleIamDAO.findServiceAccount(any[GoogleProject], any[ServiceAccountName])).thenReturn(Future.successful(None))
    when(googleIamDAO.createServiceAccount(any[GoogleProject], any[ServiceAccountName], any[ServiceAccountDisplayName])).thenReturn(Future.successful(ServiceAccount(ServiceAccountSubjectId("12312341234"), WorkbenchEmail("pet@myproject.iam.gserviceaccount.com"), ServiceAccountDisplayName(""))))
    when(googleIamDAO.createServiceAccountKey(any[GoogleProject], any[WorkbenchEmail])).thenReturn(Future.successful(ServiceAccountKey(ServiceAccountKeyId("foo"), ServiceAccountPrivateKeyData(ServiceAccountPrivateKeyData(expectedJson).encode), None, None)))
    when(googleIamDAO.removeServiceAccountKey(any[GoogleProject], any[WorkbenchEmail], any[ServiceAccountKeyId])).thenReturn(Future.successful(()))
    when(googleIamDAO.listUserManagedServiceAccountKeys(any[GoogleProject], any[WorkbenchEmail])).thenReturn(Future.successful(Seq.empty))
    when(googleIamDAO.addServiceAccountUserRoleForUser(any[GoogleProject], any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))
    (googleIamDAO, expectedJson)
  }

  def setupPetSATest(): (SamUser, SamRoutes, String) = {
    val (googleIamDAO, expectedJson: String) = createMockGoogleIamDaoForSAKeyTests
    val samUser = Generator.genWorkbenchUserGoogle.sample.get
    val (user, samDeps, routes) = createTestUser(
      configResourceTypes,
      Some(googleIamDAO),
      TestSupport.googleServicesConfig.copy(serviceAccountClientEmail = samUser.email, serviceAccountClientId = samUser.googleSubjectId.get.value),
      user = samUser
    )

    val regDAO = new MockRegistrationDAO

    samDeps.cloudExtensions.asInstanceOf[GoogleExtensions].onBoot(SamApplication(samDeps.userService,
      samDeps.resourceService, samDeps.statusService, new TosService(samDeps.directoryDAO, regDAO, "example.com", TestSupport.tosConfig))).unsafeRunSync()
    (user, routes, expectedJson)
  }
}
