package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import spray.json.DefaultJsonProtocol._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.implicits.toFoldableOps
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes.{SamResourceActionPatterns, resourceTypeAdmin}
import org.broadinstitute.dsde.workbench.sam.model.SamResourceActions._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.api._
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.scalactic.anyvals.NonEmptyList
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{AppendedClues, Assertion}
import org.mockito.scalatest.MockitoSugar

class AdminResourcesRoutesSpec extends AnyFlatSpec with Matchers with TestSupport with ScalatestRouteTest with AppendedClues with MockitoSugar {

  implicit val errorReportSource: ErrorReportSource = ErrorReportSource("sam")

  val adminUser = Generator.genFirecloudUser.sample.get
  val broadUser = Generator.genBroadInstituteUser.sample.get

  val testUser1 = Generator.genWorkbenchUserGoogle.sample.get
  val testUser2 = Generator.genWorkbenchUserGoogle.sample.get

  val defaultResourceType = ResourceType(
    ResourceTypeName("rt"),
    Set.empty,
    Set(ResourceRole(ResourceRoleName("owner"), Set(getParent))),
    ResourceRoleName("owner")
  )

  val defaultResourceId = ResourceId("foo")

  val defaultAccessPolicyMembership = AccessPolicyMembershipResponse(Set(WorkbenchEmail("testUser@example.com")), Set.empty, Set.empty, None)
  val defaultAdminPolicyName = AccessPolicyName("admin")
  val defaultAdminResourceId = FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(defaultResourceType.name.value))
  val defaultAccessPolicyResponseEntry =
    AccessPolicyResponseEntry(defaultAdminPolicyName, defaultAccessPolicyMembership, WorkbenchEmail("policy_email@example.com"))

  def withSamRoutes(
      resources: Map[ResourceTypeName, ResourceType] = Map(defaultResourceType.name -> defaultResourceType),
      admin: SamUser = adminUser,
      adminActions: Set[ResourceAction] = Set(adminReadPolicies, adminAddMember, adminRemoveMember),
      requester: SamUser = adminUser,
      users: NonEmptyList[SamUser] = NonEmptyList(testUser1, testUser2)
  )(testCode: SamRoutes => Assertion): Assertion =
    runAndWait {
      val routes = TestSamRoutes(resources, user = requester)
      for {
        _ <- (admin +: users).toSet.toList.filterNot(_ == requester).traverse_ { user =>
          routes.userService.createUser(user, samRequestContext)
        }
        _ <- routes.resourceService.createPolicy(
          FullyQualifiedPolicyId(defaultAdminResourceId, defaultAdminPolicyName),
          Set(admin.id),
          Set(ResourceRoleName("test")),
          adminActions,
          Set(),
          samRequestContext
        )
        _ <- routes.resourceService.createResource(defaultResourceType, defaultResourceId, users.head, samRequestContext)
      } yield testCode(routes)
    }

  "GET /api/admin/v1/resources/{resourceType}/{resourceId}/policies" should "200 when user has `admin_read_policies`" in
    withSamRoutes(adminActions = Set(adminReadPolicies)) { routes =>
      Get(s"/api/admin/v1/resources/${defaultResourceType.name}/$defaultResourceId/policies") ~> routes.route ~> check {
        status shouldEqual StatusCodes.OK
      }
    }

  it should "404 when a user is not an admin for that resource type" in
    withSamRoutes(requester = testUser2) { samRoutes =>
      Get(s"/api/admin/v1/resources/${defaultResourceType.name}/$defaultResourceId/policies") ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

  it should "404 when the resource type does not exist" in
    withSamRoutes(resources = Map.empty) { samRoutes =>
      Get(s"/api/admin/v1/resources/${defaultResourceType.name}/invalid/policies") ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

  "PUT /api/admin/v1/resources/{resourceType}/{resourceId}/policies/{policyName}/memberEmails/{userEmail}" should "allow resource admins to add themselves to a resource" in
    withSamRoutes(adminActions = Set(adminAddMember)) { samRoutes =>
      Put(
        s"/api/admin/v1/resources/${defaultResourceType.name}/$defaultResourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${adminUser.email}"
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.NoContent
      }
    }

  it should "404 if the resource does not exist" in
    withSamRoutes(adminActions = Set(adminRemoveMember)) { samRoutes =>
      Put(
        s"/api/admin/v1/resources/${defaultResourceType.name}/does-not-exist/policies/does-not-exist/memberEmails/${adminUser.email}"
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.Forbidden
      }
    }

  it should "403 if a user only has remove permissions" in
    withSamRoutes(adminActions = Set(adminRemoveMember)) { samRoutes =>
      Put(
        s"/api/admin/v1/resources/${defaultResourceType.name}/$defaultResourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${adminUser.email}"
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.Forbidden
      }
    }

  it should "allow resource admins to add other users to a resource" in
    withSamRoutes(adminActions = Set(adminAddMember)) { samRoutes =>
      Put(
        s"/api/admin/v1/resources/${defaultResourceType.name}/$defaultResourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${testUser2.email}"
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.NoContent
      }
    }

  it should "not allow resource admins to add others to admin resources" in
    withSamRoutes() { samRoutes =>
      Put(
        s"/api/admin/v1/resources/${resourceTypeAdmin.name}/${defaultResourceType.name}/policies/${resourceTypeAdmin.ownerRoleName}/memberEmails/${testUser1.email}"
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
    }

  it should "400 adding unknown subject" in
    withSamRoutes(users = NonEmptyList(testUser1)) { samRoutes =>
      Put(
        s"/api/admin/v1/resources/${defaultResourceType.name}/$defaultResourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${testUser2.email}"
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
    }

  it should "add duplicate subject" in
    withSamRoutes() { samRoutes =>
      Put(
        s"/api/admin/v1/resources/${defaultResourceType.name}/$defaultResourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${testUser1.email}"
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.NoContent
      }
    }

  it should "404 if policy does not exist" in
    withSamRoutes() { samRoutes =>
      Put(
        s"/api/admin/v1/resources/${defaultResourceType.name}/$defaultResourceId/policies/does-not-exist/memberEmails/${testUser1.email}"
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

  "DELETE /api/admin/v1/resources/{resourceType}/{resourceId}/policies/{policyName}/memberEmails/{userEmail}" should "allow resource admins to remove themselves from a resource" in
    withSamRoutes(adminActions = Set(adminRemoveMember)) { samRoutes =>
      runAndWait(
        samRoutes.resourceService.addSubjectToPolicy(
          FullyQualifiedPolicyId(FullyQualifiedResourceId(defaultResourceType.name, defaultResourceId), AccessPolicyName("owner")),
          adminUser.id.asInstanceOf[WorkbenchSubject],
          samRequestContext
        )
      )
      Delete(
        s"/api/admin/v1/resources/${defaultResourceType.name}/$defaultResourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${adminUser.email}"
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.NoContent
      }
    }

  it should "give a 403 if the user only has add user permissions" in
    withSamRoutes(adminActions = Set(adminAddMember)) { samRoutes =>
      runAndWait(
        samRoutes.resourceService.addSubjectToPolicy(
          FullyQualifiedPolicyId(FullyQualifiedResourceId(defaultResourceType.name, defaultResourceId), AccessPolicyName("owner")),
          adminUser.id.asInstanceOf[WorkbenchSubject],
          samRequestContext
        )
      )
      Delete(
        s"/api/admin/v1/resources/${defaultResourceType.name}/$defaultResourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${adminUser.email}"
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.Forbidden
      }
    }

  it should "allow resource admins to remove other users from a resource" in
    withSamRoutes() { samRoutes =>
      Delete(
        s"/api/admin/v1/resources/${defaultResourceType.name}/$defaultResourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${testUser1.email}"
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.NoContent
      }
    }

  it should "give a 400 if removing a user who does not exist" in
    withSamRoutes(users = NonEmptyList(testUser1)) { samRoutes =>
      Delete(
        s"/api/admin/v1/resources/${defaultResourceType.name}/$defaultResourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${testUser2.email}"
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.BadRequest
      }
    }

  it should "complete successfully when removing a user who does not have permissions on the policy" in
    withSamRoutes() { samRoutes =>
      Delete(
        s"/api/admin/v1/resources/${defaultResourceType.name}/$defaultResourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${testUser2.email}"
      ) ~> samRoutes.route ~> check {
        status shouldEqual StatusCodes.NoContent
      }
    }

  private val constrainableResourceType = ResourceType(
    ResourceTypeName("rt"),
    Set(SamResourceActionPatterns.readAuthDomain, SamResourceActionPatterns.use),
    Set(ResourceRole(ResourceRoleName("owner"), Set(readAuthDomain, ManagedGroupService.useAction))),
    ResourceRoleName("owner")
  )

  private def initManagedGroupResourceType(): ResourceType = {
    val accessPolicyNames = Set(ManagedGroupService.adminPolicyName, ManagedGroupService.memberPolicyName, ManagedGroupService.adminNotifierPolicyName)
    val policyActions: Set[ResourceAction] =
      accessPolicyNames.flatMap(policyName => Set(SamResourceActions.sharePolicy(policyName), SamResourceActions.readPolicy(policyName)))
    val resourceActions = Set(
      ResourceAction("delete"),
      ResourceAction("notify_admins"),
      ResourceAction("set_access_instructions"),
      ManagedGroupService.useAction
    ) union policyActions
    val resourceActionPatterns = resourceActions.map(action => ResourceActionPattern(action.value, "", false))
    val defaultOwnerRole = ResourceRole(ManagedGroupService.adminRoleName, resourceActions)
    val defaultMemberRole = ResourceRole(ManagedGroupService.memberRoleName, Set.empty)
    val defaultAdminNotifierRole = ResourceRole(ManagedGroupService.adminNotifierRoleName, Set(ResourceAction("notify_admins")))
    val defaultRoles = Set(defaultOwnerRole, defaultMemberRole, defaultAdminNotifierRole)
    ResourceType(ManagedGroupService.managedGroupTypeName, resourceActionPatterns, defaultRoles, ManagedGroupService.adminRoleName)
  }

  "GET /api/admin/v1/resources/{resourceType}/{resourceId}/authDomain" should "200 with auth domain when resource has one auth domain" in {
    val managedGroupResourceType = initManagedGroupResourceType()
    val resourceTypes = Map(constrainableResourceType.name -> constrainableResourceType, managedGroupResourceType.name -> managedGroupResourceType)
    val samRoutes = TestSamRoutes(resourceTypes, user = adminUser)

    runAndWait(samRoutes.userService.createUser(testUser1, samRequestContext))
    runAndWait(samRoutes.managedGroupService.createManagedGroup(ResourceId("authDomain1"), adminUser, samRequestContext = samRequestContext))

    val resourceId = ResourceId("foo")
    val policiesMap = Map(
      AccessPolicyName("ap") -> AccessPolicyMembershipRequest(
        Set(testUser1.email),
        Set(readAuthDomain, ManagedGroupService.useAction),
        Set(ResourceRoleName("owner"))
      )
    )
    runAndWait(
      samRoutes.resourceService
        .createResource(constrainableResourceType, resourceId, policiesMap, Set(WorkbenchGroupName("authDomain1")), None, testUser1.id, samRequestContext)
    )

    val adminResourceId = FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(constrainableResourceType.name.value))
    runAndWait(
      samRoutes.resourceService.createPolicy(
        FullyQualifiedPolicyId(adminResourceId, defaultAdminPolicyName),
        Set(adminUser.id),
        Set(ResourceRoleName("test")),
        Set(adminReadSummaryInformation),
        Set(),
        samRequestContext
      )
    )

    Get(s"/api/admin/v1/resources/${constrainableResourceType.name}/${resourceId.value}/authDomain") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[String]] shouldEqual Set("authDomain1")
    }
  }

  it should "200 with empty set when resource has no auth domain" in {
    val managedGroupResourceType = initManagedGroupResourceType()
    val resourceTypes = Map(constrainableResourceType.name -> constrainableResourceType, managedGroupResourceType.name -> managedGroupResourceType)
    val samRoutes = TestSamRoutes(resourceTypes, user = adminUser)

    runAndWait(samRoutes.userService.createUser(testUser1, samRequestContext))

    val resourceId = ResourceId("foo")
    val policiesMap = Map(
      AccessPolicyName("ap") -> AccessPolicyMembershipRequest(
        Set(testUser1.email),
        Set(readAuthDomain, ManagedGroupService.useAction),
        Set(ResourceRoleName("owner"))
      )
    )
    runAndWait(
      samRoutes.resourceService
        .createResource(constrainableResourceType, resourceId, policiesMap, Set.empty, None, testUser1.id, samRequestContext)
    )

    val adminResourceId = FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(constrainableResourceType.name.value))
    runAndWait(
      samRoutes.resourceService.createPolicy(
        FullyQualifiedPolicyId(adminResourceId, defaultAdminPolicyName),
        Set(adminUser.id),
        Set(ResourceRoleName("test")),
        Set(adminReadSummaryInformation),
        Set(),
        samRequestContext
      )
    )

    Get(s"/api/admin/v1/resources/${constrainableResourceType.name}/${resourceId.value}/authDomain") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[String]] shouldEqual Set.empty
    }
  }

  it should "200 with all auth domains when resource has multiple auth domains" in {
    val managedGroupResourceType = initManagedGroupResourceType()
    val resourceTypes = Map(constrainableResourceType.name -> constrainableResourceType, managedGroupResourceType.name -> managedGroupResourceType)
    val samRoutes = TestSamRoutes(resourceTypes, user = adminUser)

    runAndWait(samRoutes.userService.createUser(testUser1, samRequestContext))
    runAndWait(samRoutes.managedGroupService.createManagedGroup(ResourceId("authDomain1"), adminUser, samRequestContext = samRequestContext))
    runAndWait(samRoutes.managedGroupService.createManagedGroup(ResourceId("authDomain2"), adminUser, samRequestContext = samRequestContext))
    runAndWait(samRoutes.managedGroupService.createManagedGroup(ResourceId("authDomain3"), adminUser, samRequestContext = samRequestContext))

    val resourceId = ResourceId("foo")
    val policiesMap = Map(
      AccessPolicyName("ap") -> AccessPolicyMembershipRequest(
        Set(testUser1.email),
        Set(readAuthDomain, ManagedGroupService.useAction),
        Set(ResourceRoleName("owner"))
      )
    )
    runAndWait(
      samRoutes.resourceService.createResource(
        constrainableResourceType,
        resourceId,
        policiesMap,
        Set(WorkbenchGroupName("authDomain1"), WorkbenchGroupName("authDomain2"), WorkbenchGroupName("authDomain3")),
        None,
        testUser1.id,
        samRequestContext
      )
    )

    val adminResourceId = FullyQualifiedResourceId(resourceTypeAdmin.name, ResourceId(constrainableResourceType.name.value))
    runAndWait(
      samRoutes.resourceService.createPolicy(
        FullyQualifiedPolicyId(adminResourceId, defaultAdminPolicyName),
        Set(adminUser.id),
        Set(ResourceRoleName("test")),
        Set(adminReadSummaryInformation),
        Set(),
        samRequestContext
      )
    )

    Get(s"/api/admin/v1/resources/${constrainableResourceType.name}/${resourceId.value}/authDomain") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
      responseAs[Set[String]] shouldEqual Set("authDomain1", "authDomain2", "authDomain3")
    }
  }

  it should "404 when user does not have admin_read_summary_information" in {
    val managedGroupResourceType = initManagedGroupResourceType()
    val resourceTypes = Map(constrainableResourceType.name -> constrainableResourceType, managedGroupResourceType.name -> managedGroupResourceType)
    val samRoutes = TestSamRoutes(resourceTypes, user = testUser1)

    runAndWait(samRoutes.userService.createUser(adminUser, samRequestContext))
    runAndWait(samRoutes.managedGroupService.createManagedGroup(ResourceId("authDomain1"), adminUser, samRequestContext = samRequestContext))

    val resourceId = ResourceId("foo")
    val policiesMap = Map(
      AccessPolicyName("ap") -> AccessPolicyMembershipRequest(
        Set(adminUser.email),
        Set(readAuthDomain, ManagedGroupService.useAction),
        Set(ResourceRoleName("owner"))
      )
    )
    runAndWait(
      samRoutes.resourceService
        .createResource(constrainableResourceType, resourceId, policiesMap, Set(WorkbenchGroupName("authDomain1")), None, adminUser.id, samRequestContext)
    )

    Get(s"/api/admin/v1/resources/${constrainableResourceType.name}/${resourceId.value}/authDomain") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 when the resource type does not exist" in {
    val samRoutes = TestSamRoutes(Map.empty, user = adminUser)

    Get(s"/api/admin/v1/resources/nonexistent/foo/authDomain") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }
}
