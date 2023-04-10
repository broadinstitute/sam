package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.implicits.toFoldableOps
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes.resourceTypeAdmin
import org.broadinstitute.dsde.workbench.sam.model.SamResourceActions._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.scalactic.anyvals.NonEmptyList
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{AppendedClues, Assertion}
import org.mockito.scalatest.MockitoSugar

class AdminResourcesRoutesSpec extends AnyFlatSpec with Matchers with TestSupport with ScalatestRouteTest with AppendedClues with MockitoSugar {

  implicit val errorReportSource = ErrorReportSource("sam")

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

  val defaultAccessPolicyMembership = AccessPolicyMembership(Set(WorkbenchEmail("testUser@example.com")), Set.empty, Set.empty, None)
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
}
