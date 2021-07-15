package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes.SamResourceActionPatterns
import org.broadinstitute.dsde.workbench.sam.model._

class AdminResourceRoutesSpec extends ResourceRoutesSpec {

  private val defaultTestUser = CreateWorkbenchUser(WorkbenchUserId("testuser"), defaultGoogleSubjectId, WorkbenchEmail("testuser@foo.com"), None)

  "GET /api/resourceTypeAdmin/v1/resources/{resourceType}/{resourceId}/policies" should "200 if caller has admin_read_policies permission" in {
    // Create a user called userWithEmail and has admin_read_policies on the resource.
    val userWithEmail = CreateWorkbenchUser(WorkbenchUserId("user1"), defaultGoogleSubjectId, WorkbenchEmail("user1@foo.com"), None)
    val members = AccessPolicyMembership(Set(userWithEmail.email), Set(ResourceAction(SamResourceActions.adminReadPolicies.value)), Set.empty)
    val resourceType = ResourceType(
      ResourceTypeName("rt"),
      Set(SamResourceActionPatterns.adminReadPolicies, ResourceActionPattern(SamResourceActions.adminReadPolicies.value, "", false)),
      Set(ResourceRole(ResourceRoleName(SamResourceTypes.resourceTypeAdminName.value), Set(SamResourceActions.alterPolicies, SamResourceActions.adminReadPolicies))),
      ResourceRoleName(SamResourceTypes.resourceTypeAdminName.value))
    val samRoutes = TestSamRoutes(Map(resourceType.name -> resourceType))

    val resourceId = ResourceId("foo")
    val policyName = AccessPolicyName(SamResourceActions.adminReadPolicies.value)

    runAndWait(samRoutes.userService.createUser(defaultTestUser, samRequestContext))
    runAndWait(samRoutes.userService.createUser(userWithEmail, samRequestContext))
    createUserResourcePolicy(members, resourceType, samRoutes, resourceId, policyName)

    Get(s"/api/resourceTypeAdmin/v1/resources/${resourceType.name}/${resourceId}/policies") ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.OK
    }
  }
}
