package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{ErrorReport, ErrorReportSource, UserInfo, WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchSubject, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.{TestSupport, model}
import org.broadinstitute.dsde.workbench.sam.TestSupport.genGoogleSubjectId
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes.SamResourceActionPatterns
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

  private val defaultTestUser =  CreateWorkbenchUser(WorkbenchUserId("testuser"), genGoogleSubjectId(), WorkbenchEmail("testuser@foo.com"), None)

  private val defaultTestUserTwo =  CreateWorkbenchUser(WorkbenchUserId("testuser2"), genGoogleSubjectId(), WorkbenchEmail("testuser2@foo.com"), None)

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

  "GET /api/resourceTypeAdmin/v1/resources/{resourceType}/{resourceId}/policies/" should "200 when successful" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = true)
    val resourceId = ResourceId("foo")
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.adminReadPolicies, ResourceActionPattern(SamResourceActions.adminReadPolicies.value, "", false)),
      Set(ResourceRole(ResourceRoleName(SamResourceTypes.resourceTypeAdminName.value), Set(SamResourceActions.alterPolicies, SamResourceActions.adminReadPolicies))),
      ResourceRoleName(SamResourceTypes.resourceTypeAdminName.value))

    when(samRoutes.policyEvaluatorService.hasPermissionOneOf(any[FullyQualifiedResourceId], any[Iterable[ResourceAction]], any[WorkbenchUserId], any[SamRequestContext])).thenReturn(IO(true))
    when(samRoutes.resourceService.getResourceType(resourceType.name)).thenReturn(IO(Some(resourceType)))
    when(samRoutes.resourceService.listResourcePolicies(any[FullyQualifiedResourceId], any[SamRequestContext])).thenReturn(IO(LazyList[AccessPolicyResponseEntry]()))

    Get(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/${resourceId}/policies") ~> samRoutes.route ~> check {
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
      Set(SamResourceActionPatterns.adminReadPolicies, ResourceActionPattern(SamResourceActions.adminReadPolicies.value, "", false)),
      Set(ResourceRole(ResourceRoleName(SamResourceTypes.resourceTypeAdminName.value), Set(SamResourceActions.alterPolicies, SamResourceActions.adminReadPolicies))),
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


  "PUT /api/resourceTypeAdmin/v1/resources/{resourceType}/{resourceId}/policies/{policyName}/memberEmails/{userEmail}" should "allow resource admins to add themselves to a resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val resourceTypeAdmin = ResourceType(
      ResourceTypeName("resource_type_admin"),
      Set(),
      Set(
        ResourceRole(ResourceRoleName("owner"), Set()),
        ResourceRole(
          ResourceRoleName("resource_type_admin"),
          Set(SamResourceActions.adminRemoveMember, SamResourceActions.adminAddMember, SamResourceActions.adminReadPolicies)
        )
      ),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(samRoutes.resourceService.createPolicy(model.FullyQualifiedPolicyId(model.FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.userInfo.userId), Set(ResourceRoleName("resource_type_admin")), Set(SamResourceActions.adminAddMember), Set(), samRequestContext))

    val resourceId = ResourceId("foo")

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, UserInfo(OAuth2BearerToken("accessToken"), defaultTestUser.id, defaultTestUser.email, 0), samRequestContext))

    Put(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/memberEmails/${samRoutes.userInfo.userEmail.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "give a 403 if a user only has remove permissions" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val resourceTypeAdmin = ResourceType(
      ResourceTypeName("resource_type_admin"),
      Set(),
      Set(
        ResourceRole(ResourceRoleName("owner"), Set()),
        ResourceRole(
          ResourceRoleName("resource_type_admin"),
          Set(SamResourceActions.adminRemoveMember, SamResourceActions.adminAddMember, SamResourceActions.adminReadPolicies)
        )
      ),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(samRoutes.resourceService.createPolicy(model.FullyQualifiedPolicyId(model.FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.userInfo.userId), Set(ResourceRoleName("resource_type_admin")), Set(SamResourceActions.adminRemoveMember), Set(), samRequestContext))

    val resourceId = ResourceId("foo")

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, UserInfo(OAuth2BearerToken("accessToken"), defaultTestUser.id, defaultTestUser.email, 0), samRequestContext))

    Put(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/memberEmails/${samRoutes.userInfo.userEmail.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "allow resource admins to add other users to a resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val resourceTypeAdmin = ResourceType(
      ResourceTypeName("resource_type_admin"),
      Set(),
      Set(
        ResourceRole(ResourceRoleName("owner"), Set()),
        ResourceRole(
          ResourceRoleName("resource_type_admin"),
          Set(SamResourceActions.adminRemoveMember, SamResourceActions.adminAddMember, SamResourceActions.adminReadPolicies)
        )
      ),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(samRoutes.resourceService.createPolicy(model.FullyQualifiedPolicyId(model.FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.userInfo.userId), Set(ResourceRoleName("resource_type_admin")), Set(SamResourceActions.adminAddMember), Set(), samRequestContext))

    val resourceId = ResourceId("foo")

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    runAndWait(samRoutes.userService.createUser(defaultTestUserTwo, samRequestContext))

    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, UserInfo(OAuth2BearerToken("accessToken"), defaultTestUser.id, defaultTestUser.email, 0), samRequestContext))

    Put(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/memberEmails/${defaultTestUserTwo.email.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "400 adding unknown subject" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val resourceTypeAdmin = ResourceType(
      ResourceTypeName("resource_type_admin"),
      Set(),
      Set(
        ResourceRole(ResourceRoleName("owner"), Set()),
        ResourceRole(
          ResourceRoleName("resource_type_admin"),
          Set(SamResourceActions.adminRemoveMember, SamResourceActions.adminAddMember, SamResourceActions.adminReadPolicies)
        )
      ),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(samRoutes.resourceService.createPolicy(model.FullyQualifiedPolicyId(model.FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.userInfo.userId), Set(ResourceRoleName("resource_type_admin")), Set(SamResourceActions.adminAddMember), Set(), samRequestContext))

    val resourceId = ResourceId("foo")

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, UserInfo(OAuth2BearerToken("accessToken"), defaultTestUser.id, defaultTestUser.email, 0), samRequestContext))

    Put(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/memberEmails/${defaultTestUserTwo.email.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "add duplicate subject" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val resourceTypeAdmin = ResourceType(
      ResourceTypeName("resource_type_admin"),
      Set(),
      Set(
        ResourceRole(ResourceRoleName("owner"), Set()),
        ResourceRole(
          ResourceRoleName("resource_type_admin"),
          Set(SamResourceActions.adminRemoveMember, SamResourceActions.adminAddMember, SamResourceActions.adminReadPolicies)
        )
      ),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(samRoutes.resourceService.createPolicy(model.FullyQualifiedPolicyId(model.FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.userInfo.userId), Set(ResourceRoleName("resource_type_admin")), Set(SamResourceActions.adminAddMember), Set(), samRequestContext))

    val resourceId = ResourceId("foo")

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, UserInfo(OAuth2BearerToken("accessToken"), defaultTestUser.id, defaultTestUser.email, 0), samRequestContext))

    Put(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/memberEmails/${defaultTestUser.email.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "error if policy does not exist" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val resourceTypeAdmin = ResourceType(
      ResourceTypeName("resource_type_admin"),
      Set(),
      Set(
        ResourceRole(ResourceRoleName("owner"), Set()),
        ResourceRole(
          ResourceRoleName("resource_type_admin"),
          Set(SamResourceActions.adminRemoveMember, SamResourceActions.adminAddMember, SamResourceActions.adminReadPolicies)
        )
      ),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

//    runAndWait(samRoutes.resourceService.createPolicy(model.FullyQualifiedPolicyId(model.FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.userInfo.userId), Set(ResourceRoleName("resource_type_admin")), Set(SamResourceActions.adminAddMember), Set(), samRequestContext))

    val resourceId = ResourceId("foo")

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, UserInfo(OAuth2BearerToken("accessToken"), defaultTestUser.id, defaultTestUser.email, 0), samRequestContext))

    Put(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/memberEmails/${defaultTestUser.email.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "DELETE /api/resourceTypeAdmin/v1/resources/{resourceType}/{resourceId}/policies/{policyName}/memberEmails/{userEmail}" should "allow resource admins to remove themselves from a resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val resourceTypeAdmin = ResourceType(
      ResourceTypeName("resource_type_admin"),
      Set(),
      Set(
        ResourceRole(ResourceRoleName("owner"), Set()),
        ResourceRole(
          ResourceRoleName("resource_type_admin"),
          Set(SamResourceActions.adminRemoveMember, SamResourceActions.adminAddMember, SamResourceActions.adminReadPolicies)
        )
      ),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(samRoutes.resourceService.createPolicy(model.FullyQualifiedPolicyId(model.FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.userInfo.userId), Set(ResourceRoleName("resource_type_admin")), Set(SamResourceActions.adminRemoveMember), Set(), samRequestContext))

    val resourceId = ResourceId("foo")

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, UserInfo(OAuth2BearerToken("accessToken"), defaultTestUser.id, defaultTestUser.email, 0), samRequestContext))

    runAndWait(samRoutes.resourceService.addSubjectToPolicy(model.FullyQualifiedPolicyId(model.FullyQualifiedResourceId(resourceType.name, resourceId), AccessPolicyName("owner")), samRoutes.userInfo.userId.asInstanceOf[WorkbenchSubject], samRequestContext))

    Delete(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/memberEmails/${samRoutes.userInfo.userEmail.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "give a 403 if the user only has add user permissions" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val resourceTypeAdmin = ResourceType(
      ResourceTypeName("resource_type_admin"),
      Set(),
      Set(
        ResourceRole(ResourceRoleName("owner"), Set()),
        ResourceRole(
          ResourceRoleName("resource_type_admin"),
          Set(SamResourceActions.adminRemoveMember, SamResourceActions.adminAddMember, SamResourceActions.adminReadPolicies)
        )
      ),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(samRoutes.resourceService.createPolicy(model.FullyQualifiedPolicyId(model.FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.userInfo.userId), Set(ResourceRoleName("resource_type_admin")), Set(SamResourceActions.adminAddMember), Set(), samRequestContext))

    val resourceId = ResourceId("foo")

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    runAndWait(samRoutes.userService.createUser(defaultTestUserTwo, samRequestContext))

    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, UserInfo(OAuth2BearerToken("accessToken"), defaultTestUser.id, defaultTestUser.email, 0), samRequestContext))

    runAndWait(samRoutes.resourceService.addSubjectToPolicy(model.FullyQualifiedPolicyId(model.FullyQualifiedResourceId(resourceType.name, resourceId), AccessPolicyName("owner")), defaultTestUserTwo.id.asInstanceOf[WorkbenchSubject], samRequestContext))

    Delete(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/memberEmails/${defaultTestUserTwo.email.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "allow resource admins to remove other users from a resource" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val resourceTypeAdmin = ResourceType(
      ResourceTypeName("resource_type_admin"),
      Set(),
      Set(
        ResourceRole(ResourceRoleName("owner"), Set()),
        ResourceRole(
          ResourceRoleName("resource_type_admin"),
          Set(SamResourceActions.adminRemoveMember, SamResourceActions.adminAddMember, SamResourceActions.adminReadPolicies)
        )
      ),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(samRoutes.resourceService.createPolicy(model.FullyQualifiedPolicyId(model.FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.userInfo.userId), Set(ResourceRoleName("resource_type_admin")), Set(SamResourceActions.adminRemoveMember), Set(), samRequestContext))

    val resourceId = ResourceId("foo")

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    runAndWait(samRoutes.userService.createUser(defaultTestUserTwo, samRequestContext))

    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, UserInfo(OAuth2BearerToken("accessToken"), defaultTestUser.id, defaultTestUser.email, 0), samRequestContext))

    runAndWait(samRoutes.resourceService.addSubjectToPolicy(model.FullyQualifiedPolicyId(model.FullyQualifiedResourceId(resourceType.name, resourceId), AccessPolicyName("owner")), defaultTestUserTwo.id.asInstanceOf[WorkbenchSubject], samRequestContext))

    Delete(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/memberEmails/${defaultTestUserTwo.email.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "give a 400 if removing a user who does not exist" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val resourceTypeAdmin = ResourceType(
      ResourceTypeName("resource_type_admin"),
      Set(),
      Set(
        ResourceRole(ResourceRoleName("owner"), Set()),
        ResourceRole(
          ResourceRoleName("resource_type_admin"),
          Set(SamResourceActions.adminRemoveMember, SamResourceActions.adminAddMember, SamResourceActions.adminReadPolicies)
        )
      ),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(samRoutes.resourceService.createPolicy(model.FullyQualifiedPolicyId(model.FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.userInfo.userId), Set(ResourceRoleName("resource_type_admin")), Set(SamResourceActions.adminRemoveMember), Set(), samRequestContext))

    val resourceId = ResourceId("foo")

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

//    runAndWait(samRoutes.userService.createUser(defaultTestUserTwo, samRequestContext))

    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, UserInfo(OAuth2BearerToken("accessToken"), defaultTestUser.id, defaultTestUser.email, 0), samRequestContext))

//    runAndWait(samRoutes.resourceService.addSubjectToPolicy(model.FullyQualifiedPolicyId(model.FullyQualifiedResourceId(resourceType.name, resourceId), AccessPolicyName("owner")), defaultTestUserTwo.id.asInstanceOf[WorkbenchSubject], samRequestContext))

    Delete(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/memberEmails/${defaultTestUserTwo.email.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "complete successfully if a user does not have permissions on the policy" in {
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(),
      Set(ResourceRole(ResourceRoleName("owner"), Set())),
      ResourceRoleName("owner"))

    val resourceTypeAdmin = ResourceType(
      ResourceTypeName("resource_type_admin"),
      Set(),
      Set(
        ResourceRole(ResourceRoleName("owner"), Set()),
        ResourceRole(
          ResourceRoleName("resource_type_admin"),
          Set(SamResourceActions.adminRemoveMember, SamResourceActions.adminAddMember, SamResourceActions.adminReadPolicies)
        )
      ),
      ResourceRoleName("owner")
    )

    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    runAndWait(samRoutes.resourceService.createPolicy(model.FullyQualifiedPolicyId(model.FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(resourceType.name.value)), AccessPolicyName("resource_type_admin")), Set(samRoutes.userInfo.userId), Set(ResourceRoleName("resource_type_admin")), Set(SamResourceActions.adminRemoveMember), Set(), samRequestContext))

    val resourceId = ResourceId("foo")

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))

    runAndWait(samRoutes.userService.createUser(defaultTestUserTwo, samRequestContext))

    runAndWait(samRoutes.resourceService.createResource(resourceType, resourceId, UserInfo(OAuth2BearerToken("accessToken"), defaultTestUser.id, defaultTestUser.email, 0), samRequestContext))

    //    runAndWait(samRoutes.resourceService.addSubjectToPolicy(model.FullyQualifiedPolicyId(model.FullyQualifiedResourceId(resourceType.name, resourceId), AccessPolicyName("owner")), defaultTestUserTwo.id.asInstanceOf[WorkbenchSubject], samRequestContext))

    Delete(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/${resourceId.value}/policies/${resourceType.ownerRoleName.value}/memberEmails/${defaultTestUserTwo.email.value}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  class SamSuperAdminExtensions(isSamSuperAdmin: Boolean) extends NoExtensions {
    override def isSamSuperAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = Future.successful(isSamSuperAdmin)
  }
}
