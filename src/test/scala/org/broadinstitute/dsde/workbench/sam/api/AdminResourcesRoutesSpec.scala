package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import cats.implicits.toTraverseOps
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes.resourceTypeAdmin
import org.broadinstitute.dsde.workbench.sam.model.SamResourceActions._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.scalatest.AppendedClues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar


class AdminResourcesRoutesSpec
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

  "GET /api/resourceTypeAdmin/v1/resources/{resourceType}/{resourceId}/policies" should "200 when user has `admin_read_policies`" in {
    val samRoutes = TestSamRoutes(Map(defaultResourceType.name -> defaultResourceType), user = firecloudAdmin)

    val resourceId = ResourceId("foo")
    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(defaultAdminResourceId, defaultAdminPolicyName), Set(samRoutes.user.id), Set(ResourceRoleName("test")), Set(adminReadPolicies), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
        _ <- samRoutes.resourceService.createResource(defaultResourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Get(s"/api/resourceTypeAdmin/v1/resources/${defaultResourceType.name}/$resourceId/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }

  it should "404 when a user is not an admin for that resource type" in {
    val samRoutes = TestSamRoutes(Map(defaultResourceType.name -> defaultResourceType), user = testUser2)

    val resourceId = ResourceId("foo")
    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(defaultAdminResourceId, defaultAdminPolicyName), Set(), Set(ResourceRoleName("test")), Set(adminReadPolicies), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
        _ <- samRoutes.resourceService.createResource(defaultResourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Get(s"/api/resourceTypeAdmin/v1/resources/${defaultResourceType.name}/$resourceId/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  it should "404 when the resource type does not exist" in {
    val samRoutes = TestSamRoutes(resourceTypes = Map.empty, user = firecloudAdmin)
    Get(s"/api/resourceTypeAdmin/v1/resources/${defaultResourceType.name}/invalid/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "PUT /api/resourceTypeAdmin/v1/resources/{resourceType}/{resourceId}/policies/{policyName}/memberEmails/{userEmail}" should "allow resource admins to add themselves to a resource" in {
    val samRoutes = TestSamRoutes(Map(defaultResourceType.name -> defaultResourceType), user = firecloudAdmin)

    val resourceId = ResourceId("foo")
    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(defaultAdminResourceId, AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("admin_add_member")), Set(adminAddMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
        _ <- samRoutes.resourceService.createResource(defaultResourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Put(s"/api/resourceTypeAdmin/v1/resources/${defaultResourceType.name}/$resourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${samRoutes.user.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "403 if a user only has remove permissions" in {
    val samRoutes = TestSamRoutes(resourceTypes = Map(defaultResourceType.name -> defaultResourceType), user = firecloudAdmin)
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(defaultAdminResourceId, AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("admin_remove_member")), Set(adminRemoveMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
        _ <- samRoutes.resourceService.createResource(defaultResourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Put(s"/api/resourceTypeAdmin/v1/resources/${defaultResourceType.name}/$resourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${samRoutes.user.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "allow resource admins to add other users to a resource" in {
    val samRoutes = TestSamRoutes(Map(defaultResourceType.name -> defaultResourceType), user = firecloudAdmin)
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(defaultAdminResourceId, AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("admin_add_member")), Set(adminAddMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO {
          List(testUser1, testUser2).traverse(samRoutes.userService.createUser(_, samRequestContext))
        })
        _ <- samRoutes.resourceService.createResource(defaultResourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Put(s"/api/resourceTypeAdmin/v1/resources/${defaultResourceType.name}/$resourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${testUser2.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "not allow resource admins to add others to admin resources" in {
    val samRoutes = TestSamRoutes(Map(defaultResourceType.name -> defaultResourceType), user = firecloudAdmin)

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(defaultAdminResourceId, AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("admin_add_member")), Set(adminAddMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
      } yield ()
    }

    Put(s"/api/resourceTypeAdmin/v1/resources/${resourceTypeAdmin.name}/${defaultResourceType.name}/policies/${resourceTypeAdmin.ownerRoleName}/memberEmails/${testUser1.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "400 adding unknown subject" in {
    val samRoutes = TestSamRoutes(Map(defaultResourceType.name -> defaultResourceType), user = firecloudAdmin)
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(defaultAdminResourceId, AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("resource_type_admin")), Set(adminAddMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
        _ <- samRoutes.resourceService.createResource(defaultResourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Put(s"/api/resourceTypeAdmin/v1/resources/${defaultResourceType.name}/$resourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${testUser2.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "add duplicate subject" in {
    val samRoutes = TestSamRoutes(Map(defaultResourceType.name -> defaultResourceType), user = firecloudAdmin)
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(defaultAdminResourceId, AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("resource_type_admin")), Set(adminAddMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
        _ <- samRoutes.resourceService.createResource(defaultResourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Put(s"/api/resourceTypeAdmin/v1/resources/${defaultResourceType.name}/$resourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${testUser1.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "404 if policy does not exist" in {
    val samRoutes = TestSamRoutes(Map(defaultResourceType.name -> defaultResourceType), user = firecloudAdmin)
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(defaultAdminResourceId, AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("resource_type_admin")), Set(adminAddMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
        _ <- samRoutes.resourceService.createResource(defaultResourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Put(s"/api/resourceTypeAdmin/v1/resources/${defaultResourceType.name}/$resourceId/policies/does-not-exist/memberEmails/${testUser1.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NotFound
    }
  }

  "DELETE /api/resourceTypeAdmin/v1/resources/{resourceType}/{resourceId}/policies/{policyName}/memberEmails/{userEmail}" should "allow resource admins to remove themselves from a resource" in {
    val samRoutes = TestSamRoutes(Map(defaultResourceType.name -> defaultResourceType))
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(defaultAdminResourceId, AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("resource_type_admin")), Set(adminRemoveMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
        _ <- samRoutes.resourceService.createResource(defaultResourceType, resourceId, testUser1, samRequestContext)
        _ <- samRoutes.resourceService.addSubjectToPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(defaultResourceType.name, resourceId), AccessPolicyName("owner")), samRoutes.user.id.asInstanceOf[WorkbenchSubject], samRequestContext)
      } yield ()
    }

    Delete(s"/api/resourceTypeAdmin/v1/resources/${defaultResourceType.name}/$resourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${samRoutes.user.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "give a 403 if the user only has add user permissions" in {
    val samRoutes = TestSamRoutes(Map(defaultResourceType.name -> defaultResourceType), user = firecloudAdmin)
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(defaultAdminResourceId, AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("admin_add_member")), Set(adminAddMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO {
          List(testUser1, testUser2).traverse(samRoutes.userService.createUser(_, samRequestContext))
        })
        _ <- samRoutes.resourceService.createResource(defaultResourceType, resourceId, testUser1, samRequestContext)
        _ <- samRoutes.resourceService.addSubjectToPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(defaultResourceType.name, resourceId), AccessPolicyName("owner")), testUser2.id.asInstanceOf[WorkbenchSubject], samRequestContext)
      } yield ()
    }

    Delete(s"/api/resourceTypeAdmin/v1/resources/${defaultResourceType.name}/$resourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${testUser2.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  it should "allow resource admins to remove other users from a resource" in {
    val samRoutes = TestSamRoutes(Map(defaultResourceType.name -> defaultResourceType), user = firecloudAdmin)
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(defaultAdminResourceId, AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("resource_type_admin")), Set(adminRemoveMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO {
          List(testUser1, testUser2).traverse(samRoutes.userService.createUser(_, samRequestContext))
        })
        _ <- samRoutes.resourceService.createResource(defaultResourceType, resourceId, testUser1, samRequestContext)
        _ <- samRoutes.resourceService.addSubjectToPolicy(FullyQualifiedPolicyId(FullyQualifiedResourceId(defaultResourceType.name, resourceId), AccessPolicyName("owner")), testUser2.id.asInstanceOf[WorkbenchSubject], samRequestContext)
      } yield ()
    }

    Delete(s"/api/resourceTypeAdmin/v1/resources/${defaultResourceType.name}/$resourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${testUser2.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }

  it should "give a 400 if removing a user who does not exist" in {
    val samRoutes = TestSamRoutes(Map(defaultResourceType.name -> defaultResourceType), user = firecloudAdmin)
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(defaultAdminResourceId, AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("resource_type_admin")), Set(adminRemoveMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO(samRoutes.userService.createUser(testUser1, samRequestContext)))
        _ <- samRoutes.resourceService.createResource(defaultResourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Delete(s"/api/resourceTypeAdmin/v1/resources/${defaultResourceType.name}/$resourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${testUser2.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.BadRequest
    }
  }

  it should "complete successfully when removing a user who does not have permissions on the policy" in {
    val samRoutes = TestSamRoutes(Map(defaultResourceType.name -> defaultResourceType))
    val resourceId = ResourceId("foo")

    runAndWait {
      for {
        _ <- samRoutes.resourceService.createPolicy(FullyQualifiedPolicyId(defaultAdminResourceId, AccessPolicyName("resource_type_admin")), Set(samRoutes.user.id), Set(ResourceRoleName("resource_type_admin")), Set(adminRemoveMember), Set(), samRequestContext)
        _ <- IO.fromFuture(IO {
          List(testUser1, testUser2).traverse(samRoutes.userService.createUser(_, samRequestContext))
        })
        _ <- samRoutes.resourceService.createResource(defaultResourceType, resourceId, testUser1, samRequestContext)
      } yield ()
    }

    Delete(s"/api/resourceTypeAdmin/v1/resources/${defaultResourceType.name}/$resourceId/policies/${defaultResourceType.ownerRoleName}/memberEmails/${testUser2.email}") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.NoContent
    }
  }
}
