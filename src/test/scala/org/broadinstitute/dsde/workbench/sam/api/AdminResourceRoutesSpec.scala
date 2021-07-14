package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import org.broadinstitute.dsde.workbench.model.{ErrorReportSource, UserInfo, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport.genGoogleSubjectId
import org.broadinstitute.dsde.workbench.sam.dataAccess.{MockAccessPolicyDAO, MockDirectoryDAO, MockRegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.service._
import org.mockito.Mockito.{RETURNS_SMART_NULLS, when}
import org.scalatest.AppendedClues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

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

  "GET /api/resourceTypeAdmin/v1/resourceTypes/{resourceType}/policies" should "403 if user isn't a Sam super admin" in {
    val samRoutes = createSamRoutes(isSamSuperAdmin = false)
    val accessPolicyMembership = AccessPolicyMembership(Set(WorkbenchEmail("testUser@example.com")), Set.empty, Set.empty, None)

    Put(s"/api/resourceTypeAdmin/v1/resourceTypes/${defaultResourceType.name}/policies/admin", accessPolicyMembership) ~> samRoutes.route ~> check {
      status shouldEqual StatusCodes.Forbidden
    }
  }

  class SamSuperAdminExtensions(isSamSuperAdmin: Boolean) extends NoExtensions {
    override def isSamSuperAdmin(memberEmail: WorkbenchEmail): Future[Boolean] = Future.successful(isSamSuperAdmin)
  }
}
