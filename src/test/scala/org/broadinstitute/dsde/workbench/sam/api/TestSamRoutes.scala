package org.broadinstitute.dsde.workbench.sam.api

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives.reject
import akka.stream.Materializer
import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.TestSupport.samRequestContext
import org.broadinstitute.dsde.workbench.sam.config.{LiquibaseConfig, SwaggerConfig}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO, MockAccessPolicyDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model.{ResourceActionPattern, ResourceRole, ResourceRoleName, ResourceType, ResourceTypeName, SamResourceActions}
import org.broadinstitute.dsde.workbench.sam.service._
import org.scalatest.concurrent.ScalaFutures

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext

/**
  * Created by dvoet on 7/14/17.
  */
class TestSamRoutes(resourceService: ResourceService, policyEvaluatorService: PolicyEvaluatorService, userService: UserService, statusService: StatusService, managedGroupService: ManagedGroupService, val userInfo: UserInfo, directoryDAO: DirectoryDAO, val cloudExtensions: CloudExtensions = NoExtensions, override val createWorkbenchUser: Option[CreateWorkbenchUser] = None)(implicit override val system: ActorSystem, override val materializer: Materializer, override val executionContext: ExecutionContext)
  extends SamRoutes(resourceService, userService, statusService, managedGroupService, SwaggerConfig("", ""), directoryDAO, policyEvaluatorService, LiquibaseConfig("", false)) with MockUserInfoDirectives with ExtensionRoutes with ScalaFutures {
  def extensionRoutes: server.Route = reject
  def mockDirectoryDao: DirectoryDAO = directoryDAO
}

object TestSamRoutes {
  val defaultUserInfo = UserInfo(OAuth2BearerToken("accessToken"), WorkbenchUserId("user1"), WorkbenchEmail("user1@example.com"), 0)
  val defaultGoogleSubjectId = GoogleSubjectId("user1")

  object SamResourceActionPatterns {
    val readPolicies = ResourceActionPattern("read_policies", "", false)
    val alterPolicies = ResourceActionPattern("alter_policies", "", false)
    val delete = ResourceActionPattern("delete", "", false)

    val sharePolicy = ResourceActionPattern("share_policy::.+", "", false)
    val readPolicy = ResourceActionPattern("read_policy::.+", "", false)

    val setPublic = ResourceActionPattern("set_public", "", false)
    val setPolicyPublic = ResourceActionPattern("set_public::.+", "", false)

    val use = ResourceActionPattern("use", "", true)
    val readAuthDomain = ResourceActionPattern("read_auth_domain", "", true)

    val testActionAccess = ResourceActionPattern("test_action_access::.+", "", false)
  }

  val resourceTypeAdmin = ResourceType(
    ResourceTypeName("resource_type_admin"),
    Set(
      SamResourceActionPatterns.alterPolicies,
      SamResourceActionPatterns.readPolicies,
      SamResourceActionPatterns.sharePolicy,
      SamResourceActionPatterns.readPolicy,
      SamResourceActionPatterns.setPublic,
      SamResourceActionPatterns.setPolicyPublic
    ),
    Set(
      ResourceRole(
        ResourceRoleName("owner"),
        Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies, SamResourceActions.setPublic))),
    ResourceRoleName("owner")
  )

  def apply(resourceTypes: Map[ResourceTypeName, ResourceType], userInfo: UserInfo = defaultUserInfo, policyAccessDAO: Option[AccessPolicyDAO] = None, policies: Option[mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup]] = None)(implicit system: ActorSystem, materializer: Materializer, executionContext: ExecutionContext, contextShift: ContextShift[IO]) = {
    val resourceTypesWithAdmin = resourceTypes + (resourceTypeAdmin.name -> resourceTypeAdmin)
    // need to make sure MockDirectoryDAO and MockAccessPolicyDAO share the same groups
    val groups: mutable.Map[WorkbenchGroupIdentity, WorkbenchGroup] = policies.getOrElse(new TrieMap())
    val directoryDAO = new MockDirectoryDAO(groups)
    val registrationDAO = new MockDirectoryDAO()
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val policyDAO = policyAccessDAO.getOrElse(new MockAccessPolicyDAO(Map.empty[ResourceTypeName, ResourceType], groups))

    val emailDomain = "example.com"
    val policyEvaluatorService = PolicyEvaluatorService(emailDomain, resourceTypesWithAdmin, policyDAO, directoryDAO)
    val mockResourceService = new ResourceService(resourceTypesWithAdmin, policyEvaluatorService, policyDAO, directoryDAO, NoExtensions, emailDomain)
    val mockUserService = new UserService(directoryDAO, NoExtensions, registrationDAO, Seq.empty)
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, policyEvaluatorService, resourceTypesWithAdmin, policyDAO, directoryDAO, NoExtensions, emailDomain)
    TestSupport.runAndWait(mockUserService.createUser(CreateWorkbenchUser(userInfo.userId, defaultGoogleSubjectId, userInfo.userEmail, None), samRequestContext))
    val allUsersGroup = TestSupport.runAndWait(NoExtensions.getOrCreateAllUsersGroup(directoryDAO, samRequestContext))
    TestSupport.runAndWait(googleDirectoryDAO.createGroup(allUsersGroup.id.toString, allUsersGroup.email))
    mockResourceService.initResourceTypes(samRequestContext).unsafeRunSync()

    val mockStatusService = new StatusService(directoryDAO, NoExtensions, TestSupport.dbRef)

    new TestSamRoutes(mockResourceService, policyEvaluatorService, mockUserService, mockStatusService, mockManagedGroupService, userInfo, directoryDAO)
  }
}
