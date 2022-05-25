package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import cats.implicits.toTraverseOps
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport.googleServicesConfig
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes.{SamResourceActionPatterns, resourceTypeAdmin}
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


class AdminResourceRoutesSpec
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
  val defaultResourceId = FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(defaultResourceType.name.value))
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

    val cloudExtensions = new SamSuperAdminExtensions(isSamSuperAdmin)

    val mockUserService = new UserService(directoryDAO, cloudExtensions, registrationDAO, Seq.empty,
      new TosService(directoryDAO, registrationDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig)
    )
    val mockStatusService = new StatusService(directoryDAO, registrationDAO, cloudExtensions, TestSupport.dbRef)
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, policyEvaluatorService, resourceTypes, accessPolicyDAO, directoryDAO, cloudExtensions, emailDomain)

    runAndWait(mockUserService.createUser(user, samRequestContext))

    new TestSamRoutes(mockResourceService, policyEvaluatorService, mockUserService, mockStatusService, mockManagedGroupService, user, directoryDAO, registrationDAO, cloudExtensions)
  }

  "GET /api/resourceTypeAdmin/v1/resources/{resourceType}/{resourceId}/policies/" should "200 when successful" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)
    val resourceId = ResourceId("foo")
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.adminReadPolicies, ResourceActionPattern(adminReadPolicies.value, "", false)),
      Set(ResourceRole(ResourceRoleName(SamResourceTypes.resourceTypeAdminName.value), Set(alterPolicies, adminReadPolicies))),
      ResourceRoleName(SamResourceTypes.resourceTypeAdminName.value))

    when(samRoutes.policyEvaluatorService.hasPermissionOneOf(any[FullyQualifiedResourceId], any[Iterable[ResourceAction]], any[WorkbenchUserId], any[SamRequestContext])).thenReturn(IO(true))
    when(samRoutes.resourceService.getResourceType(resourceType.name)).thenReturn(IO(Some(resourceType)))
    when(samRoutes.resourceService.listResourcePolicies(any[FullyQualifiedResourceId], any[SamRequestContext])).thenReturn(IO(LazyList[AccessPolicyResponseEntry]()))

    Get(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/$resourceId/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  it should "404 when a user has no permissions" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)
    when(samRoutes.policyEvaluatorService.hasPermissionOneOf(any[FullyQualifiedResourceId], any[Iterable[ResourceAction]], any[WorkbenchUserId], any[SamRequestContext])).thenReturn(IO(false))
    when(samRoutes.policyEvaluatorService.listUserResourceActions(any[FullyQualifiedResourceId], any[WorkbenchUserId], any[SamRequestContext])).thenReturn(IO(Set[ResourceAction]()))

    Get("/api/resourceTypeAdmin/v1/resources/rt/foo/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 with an invalid resource type" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)
    val resourceId = ResourceId("foo")
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.adminReadPolicies, ResourceActionPattern(adminReadPolicies.value, "", false)),
      Set(ResourceRole(ResourceRoleName(SamResourceTypes.resourceTypeAdminName.value), Set(alterPolicies, adminReadPolicies))),
      ResourceRoleName(SamResourceTypes.resourceTypeAdminName.value))

    when(samRoutes.policyEvaluatorService.hasPermissionOneOf(any[FullyQualifiedResourceId], any[Iterable[ResourceAction]], any[WorkbenchUserId], any[SamRequestContext])).thenReturn(IO(true))
    when(samRoutes.resourceService.getResourceType(resourceType.name)).thenReturn(IO(None))

    Get(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/${resourceId}/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
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

    Get(s"/api/resourceTypeAdmin/v1/resourceTypes/${fakeResourceTypeName}/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PUT /api/resourceTypeAdmin/v1/resourceTypes/{resourceType}/policies/{policyName}" should "201 when successful" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)

    when(samRoutes.resourceService.overwriteAdminPolicy(mockitoEq(resourceTypeAdmin), mockitoEq(defaultAdminPolicyName), mockitoEq(defaultResourceId), mockitoEq(defaultAccessPolicyMembership), any[SamRequestContext])).thenReturn(IO(null))
    when(samRoutes.resourceService.listResourcePolicies(mockitoEq(defaultResourceId), any[SamRequestContext])).thenReturn(IO(LazyList(defaultAccessPolicyResponseEntry)))

    Put(s"/api/resourceTypeAdmin/v1/resourceTypes/${defaultResourceType.name}/policies/$defaultAdminPolicyName", defaultAccessPolicyMembership) ~> samRoutes.route ~> check {
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

    Put(s"/api/resourceTypeAdmin/v1/resourceTypes/${defaultResourceType.name}/policies/$defaultAdminPolicyName", defaultAccessPolicyMembership) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if given nonexistent resource type" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)
    val fakeResourceTypeName = ResourceTypeName("does_not_exist")
    when(samRoutes.resourceService.getResourceType(fakeResourceTypeName)).thenReturn(IO(None))

    Put(s"/api/resourceTypeAdmin/v1/resourceTypes/${fakeResourceTypeName.value}/policies/$defaultAdminPolicyName", defaultAccessPolicyMembership) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "DELETE /api/resourceTypeAdmin/v1/resourceTypes/{resourceType}/policies/{policyName}" should "204 when successful" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)

    when(samRoutes.resourceService.deletePolicy(mockitoEq(FullyQualifiedPolicyId(defaultResourceId, defaultAdminPolicyName)), any[SamRequestContext])).thenReturn(IO.unit)

    Delete(s"/api/resourceTypeAdmin/v1/resourceTypes/${defaultResourceType.name}/policies/$defaultAdminPolicyName") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "403 if user isn't a Sam super admin" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = false)

    Delete(s"/api/resourceTypeAdmin/v1/resourceTypes/${defaultResourceType.name}/policies/$defaultAdminPolicyName") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "404 if given nonexistent resource type" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)
    val fakeResourceTypeName = ResourceTypeName("does_not_exist")
    when(samRoutes.resourceService.getResourceType(fakeResourceTypeName)).thenReturn(IO(None))

    Delete(s"/api/resourceTypeAdmin/v1/resourceTypes/${fakeResourceTypeName}/policies/$defaultAdminPolicyName") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 if policy does not exist" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)
    val fakePolicyName = AccessPolicyName("does_not_exist")
    when(samRoutes.resourceService.deletePolicy(mockitoEq(FullyQualifiedPolicyId(defaultResourceId, fakePolicyName)), any[SamRequestContext]))
      .thenThrow(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, "policy not found")))

    Delete(s"/api/resourceTypeAdmin/v1/resourceTypes/${defaultResourceType.name}/policies/$fakePolicyName") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }


  "PUT /api/resourceTypeAdmin/v1/resources/{resourceType}/{resourceId}/policies/{policyName}/memberEmails/{userEmail}" should "allow resource admins to add themselves to a resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType), user = firecloudAdmin)

    val resourceId = ResourceId("foo")
    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("admin_add_member")), Set(adminAddMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
        _ <- samRoutes.resourceService.createResource(resourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Put(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/$resourceId/policies/${resourceType.ownerRoleName}/memberEmails/${samRoutes.user.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "give a 403 if a user only has remove permissions" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(resourceTypes = Map(resourceType.name -> resourceType), user = firecloudAdmin)

    val resourceId = ResourceId("foo")
    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("admin_remove_member")), Set(adminRemoveMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
        _ <- samRoutes.resourceService.createResource(resourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Put(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/$resourceId/policies/${resourceType.ownerRoleName}/memberEmails/${samRoutes.user.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "allow resource admins to add other users to a resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType), user = firecloudAdmin)
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("resource_type_admin")), Set(adminAddMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO {
          List(testUser1, testUser2).traverse(samRoutes.userService.createUser(_, samRequestContext))
        })
        _ <- samRoutes.resourceService.createResource(resourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Put(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/$resourceId/policies/${resourceType.ownerRoleName}/memberEmails/${testUser2.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "400 adding unknown subject" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType), user = firecloudAdmin)
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("resource_type_admin")), Set(adminAddMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
        _ <- samRoutes.resourceService.createResource(resourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Put(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/$resourceId/policies/${resourceType.ownerRoleName}/memberEmails/${testUser2.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "add duplicate subject" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType), user = firecloudAdmin)
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("resource_type_admin")), Set(adminAddMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
        _ <- samRoutes.resourceService.createResource(resourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Put(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/$resourceId/policies/${resourceType.ownerRoleName}/memberEmails/${testUser1.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "404 if policy does not exist" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType), user = firecloudAdmin)
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("resource_type_admin")), Set(adminAddMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
        _ <- samRoutes.resourceService.createResource(resourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Put(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/$resourceId/policies/does-not-exist/memberEmails/${testUser1.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "DELETE /api/resourceTypeAdmin/v1/resources/{resourceType}/{resourceId}/policies/{policyName}/memberEmails/{userEmail}" should "allow resource admins to remove themselves from a resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("resource_type_admin")), Set(adminRemoveMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
        _ <- samRoutes.resourceService.createResource(resourceType, resourceId, testUser1, samRequestContext)
        _ <- samRoutes.resourceService.addSubjectToPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceType.name, resourceId), AccessPolicyName("owner")), samRoutes.user.id.asInstanceOf[WorkbenchSubject], samRequestContext)
      } yield ()
    }

    Delete(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/$resourceId/policies/${resourceType.ownerRoleName}/memberEmails/${samRoutes.user.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "give a 403 if the user only has add user permissions" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType), user = firecloudAdmin)
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("admin_add_member")), Set(adminAddMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO {
          List(testUser1, testUser2).traverse(samRoutes.userService.createUser(_, samRequestContext))
        })
        _ <- samRoutes.resourceService.createResource(resourceType, resourceId, testUser1, samRequestContext)
        _ <- samRoutes.resourceService.addSubjectToPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceType.name, resourceId), AccessPolicyName("owner")), testUser2.id.asInstanceOf[WorkbenchSubject], samRequestContext)
      } yield ()
    }

    Delete(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/$resourceId/policies/${resourceType.ownerRoleName}/memberEmails/${testUser2.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "allow resource admins to remove other users from a resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType), user = firecloudAdmin)
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("resource_type_admin")), Set(adminRemoveMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO {
          List(testUser1, testUser2).traverse(samRoutes.userService.createUser(_, samRequestContext))
        })
        _ <- samRoutes.resourceService.createResource(resourceType, resourceId, testUser1, samRequestContext)
        _ <- samRoutes.resourceService.addSubjectToPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceType.name, resourceId), AccessPolicyName("owner")), testUser2.id.asInstanceOf[WorkbenchSubject], samRequestContext)
      } yield ()
    }

    Delete(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/$resourceId/policies/${resourceType.ownerRoleName}/memberEmails/${testUser2.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "give a 400 if removing a user who does not exist" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType), user = firecloudAdmin)
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("resource_type_admin")), Set(adminRemoveMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
        _ <- samRoutes.resourceService.createResource(resourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Delete(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/$resourceId/policies/${resourceType.ownerRoleName}/memberEmails/${testUser2.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "complete successfully when removing a user who does not have permissions on the policy" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("resource_type_admin")), Set(adminRemoveMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO {
          List(testUser1, testUser2).traverse(samRoutes.userService.createUser(_, samRequestContext))
        })
        _ <- samRoutes.resourceService.createResource(resourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Delete(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/$resourceId/policies/${resourceType.ownerRoleName}/memberEmails/${testUser2.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  class SamSuperAdminExtensions(isSamSuperAdmin: Boolean) extends NoExtensions {
    override def isSamSuperAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = Future.successful(isSamSuperAdmin)
  }
}
