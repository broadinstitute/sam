package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import cats.implicits.catsSyntaxOptionId
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport.googleServicesConfig
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes.resourceTypeAdmin
import org.broadinstitute.dsde.workbench.sam.dataAccess.{MockAccessPolicyDAO, MockDirectoryDAO, MockRegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model.SamResourceActions._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.mockito.ArgumentMatchers.{any, eq => mockitoEq}
import org.mockito.Mockito.{RETURNS_SMART_NULLS, when}
import org.scalatest.AppendedClues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import spray.json.DefaultJsonProtocol._

import scala.concurrent.Future


class AdminResourceTypesRoutesSpec
  extends AnyFlatSpec
    with Matchers
    with TestSupport
    with ScalatestRouteTest
    with AppendedClues
    with MockitoSugar {

  implicit val errorReportSource = ErrorReportSource("sam")

  val firecloudAdmin = Generator.genFirecloudUser.sample.get
  val broadUser = Generator.genBroadInstituteUser.sample.get

  val testUser1 =  Generator.genWorkbenchUserGoogle.sample.get
  val testUser2 = Generator.genWorkbenchUserGoogle.sample.get

  val defaultResourceType = ResourceType(
    ResourceTypeName("rt"),
    Set.empty,
    Set(ResourceRole(ResourceRoleName("owner"), Set(getParent))),
    ResourceRoleName("owner")
  )

  val defaultAccessPolicyMembership = AccessPolicyMembership(Set(WorkbenchEmail("testUser@example.com")), Set.empty, Set.empty, None)
  val defaultAdminPolicyName = AccessPolicyName("admin")
  val defaultAdminResourceId = FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(defaultResourceType.name.value))
  val defaultAccessPolicyResponseEntry = AccessPolicyResponseEntry(defaultAdminPolicyName, defaultAccessPolicyMembership, WorkbenchEmail("policy_email@example.com"))

  private def createSamRoutes(resourceTypes: Map[ResourceTypeName, ResourceType] = Map(
                                defaultResourceType.name -> defaultResourceType,
                                resourceTypeAdmin.name -> resourceTypeAdmin
                              ),
                              isSamSuperAdmin: Boolean,
                              user: SamUser = firecloudAdmin): SamRoutes = {
    val directoryDAO = new MockDirectoryDAO()
    val accessPolicyDAO = new MockAccessPolicyDAO(resourceTypes, directoryDAO)
    val registrationDAO = new MockRegistrationDAO()
    val emailDomain = "example.com"

    val policyEvaluatorService = mock[PolicyEvaluatorService](RETURNS_SMART_NULLS)
    val mockResourceService = mock[ResourceService](RETURNS_SMART_NULLS)
    resourceTypes.map { case (resourceTypeName, resourceType) =>
      when(mockResourceService.getResourceType(resourceTypeName)).thenReturn(IO(Option(resourceType)))
    }

    val cloudExtensions = SamSuperAdminExtensions(isSamSuperAdmin)

    val tosService = new TosService(directoryDAO, registrationDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig)
    val mockUserService = new UserService(directoryDAO, cloudExtensions, registrationDAO, Seq.empty, tosService)
    val mockStatusService = new StatusService(directoryDAO, registrationDAO, cloudExtensions, TestSupport.dbRef)
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, policyEvaluatorService, resourceTypes, accessPolicyDAO, directoryDAO, cloudExtensions, emailDomain)

    runAndWait(mockUserService.createUser(user, samRequestContext))

    new TestSamRoutes(mockResourceService, policyEvaluatorService, mockUserService, mockStatusService, mockManagedGroupService, user, directoryDAO, registrationDAO, cloudExtensions, tosService = tosService)
  }

  "GET /api/admin/v1/resourceTypes/{resourceType}/policies/" should "200 when successful" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)

    when(samRoutes.resourceService.listResourcePolicies(mockitoEq(defaultAdminResourceId), any[SamRequestContext])).thenReturn(IO(LazyList(defaultAccessPolicyResponseEntry)))

    Get(s"/api/admin/v1/resourceTypes/${defaultResourceType.name}/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[Set[AccessPolicyResponseEntry]]
      response.map(_.policyName).contains(defaultAdminPolicyName) shouldBe true
      response.map(_.policy).contains(defaultAccessPolicyMembership) shouldBe true
    }
  }

  it should "403 if user isn't a Sam super admin" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = false)

    Get(s"/api/admin/v1/resourceTypes/${defaultResourceType.name}/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if given nonexistent resource type" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)
    val fakeResourceTypeName = ResourceTypeName("does_not_exist")

    when(samRoutes.resourceService.getResourceType(fakeResourceTypeName)).thenReturn(IO(None))

    Get(s"/api/admin/v1/resourceTypes/${fakeResourceTypeName}/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PUT /api/admin/v1/resourceTypes/{resourceType}/policies/{policyName}" should "201 when successful" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)

    when(samRoutes.resourceService.overwriteAdminPolicy(mockitoEq(resourceTypeAdmin), mockitoEq(defaultAdminPolicyName), mockitoEq(defaultAdminResourceId), mockitoEq(defaultAccessPolicyMembership), any[SamRequestContext])).thenReturn(IO(null))
    when(samRoutes.resourceService.listResourcePolicies(mockitoEq(defaultAdminResourceId), any[SamRequestContext])).thenReturn(IO(LazyList(defaultAccessPolicyResponseEntry)))

    Put(s"/api/admin/v1/resourceTypes/${defaultResourceType.name}/policies/$defaultAdminPolicyName", defaultAccessPolicyMembership) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Created
    }

    Get(s"/api/admin/v1/resourceTypes/${defaultResourceType.name}/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      val response = responseAs[Set[AccessPolicyResponseEntry]]
      response.map(_.policyName).contains(defaultAdminPolicyName) shouldBe true
      response.map(_.policy).contains(defaultAccessPolicyMembership) shouldBe true
    }
  }

  it should "403 if user isn't a Sam super admin" in {
    val samRoutes = TestSamRoutes(Map(defaultResourceType.name -> defaultResourceType), user = firecloudAdmin,
      cloudExtensions = SamSuperAdminExtensions(isSamSuperAdmin = false).some
    )

    Put(s"/api/admin/v1/resourceTypes/${defaultResourceType.name}/policies/$defaultAdminPolicyName", defaultAccessPolicyMembership) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if given nonexistent resource type" in {
    val samRoutes = TestSamRoutes(Map.empty, user = firecloudAdmin,
      cloudExtensions = SamSuperAdminExtensions(isSamSuperAdmin = true).some
    )

    Put(s"/api/admin/v1/resourceTypes/${defaultResourceType.name}/policies/$defaultAdminPolicyName", defaultAccessPolicyMembership) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "400 if a user's email domain is not in a pre-defined approve list" in {
    val samRoutes = TestSamRoutes(Map(defaultResourceType.name -> defaultResourceType),
      user = firecloudAdmin,
      cloudExtensions = SamSuperAdminExtensions(isSamSuperAdmin = true).some
    )

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(defaultAdminResourceId, defaultAdminPolicyName), Set(samRoutes.user.id), Set(ResourceRoleName("test")), Set(adminAddMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
      } yield ()
    }

    Put(s"/api/admin/v1/resourceTypes/${defaultResourceType.name}/policies/$defaultAdminPolicyName", defaultAccessPolicyMembership) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  "DELETE /api/admin/v1/resourceTypes/{resourceType}/policies/{policyName}" should "204 when successful" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)

    when(samRoutes.resourceService.deletePolicy(mockitoEq(FullyQualifiedPolicyId(defaultAdminResourceId, defaultAdminPolicyName)), any[SamRequestContext])).thenReturn(IO.unit)

    Delete(s"/api/admin/v1/resourceTypes/${defaultResourceType.name}/policies/$defaultAdminPolicyName") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "403 if user isn't a Sam super admin" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = false)

    Delete(s"/api/admin/v1/resourceTypes/${defaultResourceType.name}/policies/$defaultAdminPolicyName") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if given nonexistent resource type" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)
    val fakeResourceTypeName = ResourceTypeName("does_not_exist")
    when(samRoutes.resourceService.getResourceType(fakeResourceTypeName)).thenReturn(IO(None))

    Delete(s"/api/admin/v1/resourceTypes/${fakeResourceTypeName}/policies/$defaultAdminPolicyName") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 if policy does not exist" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)
    val fakePolicyName = AccessPolicyName("does_not_exist")
    when(samRoutes.resourceService.deletePolicy(mockitoEq(FullyQualifiedPolicyId(defaultAdminResourceId, fakePolicyName)), any[SamRequestContext]))
      .thenThrow(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "policy not found")))

    Delete(s"/api/admin/v1/resourceTypes/${defaultResourceType.name}/policies/$fakePolicyName") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  case class SamSuperAdminExtensions(isSamSuperAdmin: Boolean) extends NoExtensions {
    override def isSamSuperAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = Future.successful(isSamSuperAdmin)
  }
}
