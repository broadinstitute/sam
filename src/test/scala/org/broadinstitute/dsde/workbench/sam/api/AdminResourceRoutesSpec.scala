package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{ErrorReport, ErrorReportSource, UserInfo, WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport.genGoogleSubjectId
import org.broadinstitute.dsde.workbench.sam.dataAccess.{MockAccessPolicyDAO, MockDirectoryDAO, MockRegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers.{any, eq => mockitoEq}
import org.mockito.Mockito.{RETURNS_SMART_NULLS, when}
import org.scalatest.AppendedClues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import spray.json.DefaultJsonProtocol._

import scala.concurrent.Future


class AdminResourceRoutesSpec extends AnyFlatSpec with Matchers with TestSupport with ScalatestRouteTest with AppendedClues with MockitoSugar {
  implicit val errorReportSource = ErrorReportSource("sam")

  val defaultUserInfo = UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)

  val defaultResourceType = ResourceType(
    ResourceTypeName("rt"),
    Set.empty,
    Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.getParent))),
    ResourceRoleName("owner")
  )

  val resourceTypeAdmin = ResourceType(
    ResourceTypeName("resource_type_admin"),
    Set.empty,
    Set(ResourceRole(ResourceRoleName("owner"), Set(SamResourceActions.readPolicies))),
    ResourceRoleName("owner")
  )

  val defaultAccessPolicyMembership = AccessPolicyMembership(Set(WorkbenchEmail("testUser@example.com")), Set.empty, Set.empty, None)
  val defaultAdminPolicyName = AccessPolicyName("admin")
  val defaultResourceId = FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(defaultResourceType.name.value))
  val defaultAccessPolicyResponseEntry = AccessPolicyResponseEntry(defaultAdminPolicyName, defaultAccessPolicyMembership, WorkbenchEmail("policy_email@example.com"))

  private def createSamRoutes(resourceTypes: Map[ResourceTypeName, ResourceType] = Map(defaultResourceType.name -> defaultResourceType, resourceTypeAdmin.name -> resourceTypeAdmin),
                              isSamSuperAdmin: Boolean,
                              userInfo: UserInfo = defaultUserInfo): SamRoutes = {
    val accessPolicyDAO = new MockAccessPolicyDAO(resourceTypes)
    val directoryDAO = new MockDirectoryDAO()
    val registrationDAO = new MockRegistrationDAO()
    val emailDomain = "example.com"

    val policyEvaluatorService = mock[PolicyEvaluatorService](RETURNS_SMART_NULLS)
    val mockResourceService = mock[ResourceService](RETURNS_SMART_NULLS)
    resourceTypes.map { case (resourceTypeName, resourceType) =>
      when(mockResourceService.getResourceType(resourceTypeName)).thenReturn(IO(Option(resourceType)))
    }

    val cloudExtensions = new SamSuperAdminExtensions(isSamSuperAdmin)

    val mockUserService = new UserService(directoryDAO, cloudExtensions, registrationDAO, Seq.empty)
    val mockStatusService = new StatusService(directoryDAO, registrationDAO, cloudExtensions, TestSupport.dbRef)
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, policyEvaluatorService, resourceTypes, accessPolicyDAO, directoryDAO, cloudExtensions, emailDomain)

    mockUserService.createUser(CreateWorkbenchUser(defaultUserInfo.userId, genGoogleSubjectId(), defaultUserInfo.userEmail, None), samRequestContext)

    new TestSamRoutes(mockResourceService, policyEvaluatorService, mockUserService, mockStatusService, mockManagedGroupService, userInfo, directoryDAO, cloudExtensions)
  }


  "GET /api/resourceTypeAdmin/v1/resourceTypes/{resourceType}/policies/" should "200 when successful" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)

    when(samRoutes.resourceService.listResourcePolicies(mockitoEq(defaultResourceId), any[SamRequestContext])).thenReturn(IO(LazyList(defaultAccessPolicyResponseEntry)))

    Get(s"/api/resourceTypeAdmin/v1/resourceTypes/${defaultResourceType.name}/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[Set[AccessPolicyResponseEntry]]
      response.map(_.policyName).contains(defaultAdminPolicyName) shouldBe true
      response.map(_.policy).contains(defaultAccessPolicyMembership) shouldBe true
    }
  }

  it should "403 if user isn't a Sam super admin" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = false)

    Get(s"/api/resourceTypeAdmin/v1/resourceTypes/${defaultResourceType.name}/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if given nonexistent resource type" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)
    val fakeResourceTypeName = ResourceTypeName("does_not_exist")

    when(samRoutes.resourceService.getResourceType(fakeResourceTypeName)).thenReturn(IO(None))

    Get(s"/api/resourceTypeAdmin/v1/resourceTypes/${fakeResourceTypeName.value}/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PUT /api/resourceTypeAdmin/v1/resourceTypes/{resourceType}/policies/{policyName}" should "201 when successful" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)

    when(samRoutes.resourceService.overwritePolicy(mockitoEq(resourceTypeAdmin), mockitoEq(defaultAdminPolicyName), mockitoEq(defaultResourceId), mockitoEq(defaultAccessPolicyMembership), any[SamRequestContext])).thenReturn(IO(null))
    when(samRoutes.resourceService.listResourcePolicies(mockitoEq(defaultResourceId), any[SamRequestContext])).thenReturn(IO(LazyList(defaultAccessPolicyResponseEntry)))

    Put(s"/api/resourceTypeAdmin/v1/resourceTypes/${defaultResourceType.name}/policies/${defaultAdminPolicyName.value}", defaultAccessPolicyMembership) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    Get(s"/api/resourceTypeAdmin/v1/resourceTypes/${defaultResourceType.name}/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[Set[AccessPolicyResponseEntry]]
      response.map(_.policyName).contains(defaultAdminPolicyName) shouldBe true
      response.map(_.policy).contains(defaultAccessPolicyMembership) shouldBe true
    }
  }

  it should "403 if user isn't a Sam super admin" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = false)

    Put(s"/api/resourceTypeAdmin/v1/resourceTypes/${defaultResourceType.name}/policies/${defaultAdminPolicyName.value}", defaultAccessPolicyMembership) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if given nonexistent resource type" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)
    val fakeResourceTypeName = ResourceTypeName("does_not_exist")
    when(samRoutes.resourceService.getResourceType(fakeResourceTypeName)).thenReturn(IO(None))

    Put(s"/api/resourceTypeAdmin/v1/resourceTypes/${fakeResourceTypeName.value}/policies/${defaultAdminPolicyName.value}", defaultAccessPolicyMembership) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "DELETE /api/resourceTypeAdmin/v1/resourceTypes/{resourceType}/policies/{policyName}" should "204 when successful" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)

    when(samRoutes.resourceService.deletePolicy(mockitoEq(FullyQualifiedPolicyId(defaultResourceId, defaultAdminPolicyName)), any[SamRequestContext])).thenReturn(IO.unit)

    Delete(s"/api/resourceTypeAdmin/v1/resourceTypes/${defaultResourceType.name}/policies/${defaultAdminPolicyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "403 if user isn't a Sam super admin" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = false)

    Delete(s"/api/resourceTypeAdmin/v1/resourceTypes/${defaultResourceType.name}/policies/${defaultAdminPolicyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if given nonexistent resource type" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)
    val fakeResourceTypeName = ResourceTypeName("does_not_exist")
    when(samRoutes.resourceService.getResourceType(fakeResourceTypeName)).thenReturn(IO(None))

    Delete(s"/api/resourceTypeAdmin/v1/resourceTypes/${fakeResourceTypeName.value}/policies/${defaultAdminPolicyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 if policy does not exist" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)
    val fakePolicyName = AccessPolicyName("does_not_exist")
    when(samRoutes.resourceService.deletePolicy(mockitoEq(FullyQualifiedPolicyId(defaultResourceId, fakePolicyName)), any[SamRequestContext]))
      .thenThrow(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "policy not found")))

    Delete(s"/api/resourceTypeAdmin/v1/resourceTypes/${defaultResourceType.name}/policies/${fakePolicyName.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  class SamSuperAdminExtensions(isSamSuperAdmin: Boolean) extends NoExtensions {
    override def isSamSuperAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = Future.successful(isSamSuperAdmin)
  }
}
