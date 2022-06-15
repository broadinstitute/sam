package org.broadinstitute.dsde.workbench.sam.api

import akka.actor.ActorSystem
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives.reject
import akka.stream.Materializer
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.oauth2.mock.FakeOpenIDConnectConfiguration
import org.broadinstitute.dsde.workbench.sam.TestSupport.{googleServicesConfig, samRequestContext}
import org.broadinstitute.dsde.workbench.sam.config.{LiquibaseConfig, TermsOfServiceConfig}
import org.broadinstitute.dsde.workbench.sam.dataAccess._
import org.broadinstitute.dsde.workbench.sam.model.SamResourceActions.{adminAddMember, adminReadPolicies, adminRemoveMember}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.ExecutionContext

/**
  * Created by dvoet on 7/14/17.
  */
class TestSamRoutes(resourceService: ResourceService, policyEvaluatorService: PolicyEvaluatorService, userService: UserService, statusService: StatusService, managedGroupService: ManagedGroupService, val user: SamUser, directoryDAO: DirectoryDAO, registrationDAO: RegistrationDAO, val cloudExtensions: CloudExtensions = NoExtensions, override val newSamUser: Option[SamUser] = None, tosService: TosService)(implicit override val system: ActorSystem, override val materializer: Materializer, override val executionContext: ExecutionContext)
  extends SamRoutes(resourceService, userService, statusService, managedGroupService, TermsOfServiceConfig(false, false, "0", "app.terra.bio/#terms-of-service"), directoryDAO, registrationDAO, policyEvaluatorService, tosService, LiquibaseConfig("", false), FakeOpenIDConnectConfiguration) with MockSamUserDirectives with ExtensionRoutes with ScalaFutures {
  def extensionRoutes(samUser: SamUser, samRequestContext: SamRequestContext): server.Route = reject
  def mockDirectoryDao: DirectoryDAO = directoryDAO
  def mockRegistrationDao: RegistrationDAO = registrationDAO
}

class TestSamTosEnabledRoutes(resourceService: ResourceService, policyEvaluatorService: PolicyEvaluatorService, userService: UserService, statusService: StatusService, managedGroupService: ManagedGroupService, val user: SamUser, directoryDAO: DirectoryDAO, registrationDAO: RegistrationDAO, val cloudExtensions: CloudExtensions = NoExtensions, override val newSamUser: Option[SamUser] = None, tosService: TosService)(implicit override val system: ActorSystem, override val materializer: Materializer, override val executionContext: ExecutionContext)
  extends SamRoutes(resourceService, userService, statusService, managedGroupService, TermsOfServiceConfig(true, false, "0", "app.terra.bio/#terms-of-service"), directoryDAO, registrationDAO, policyEvaluatorService, tosService, LiquibaseConfig("", false), FakeOpenIDConnectConfiguration) with MockSamUserDirectives with ExtensionRoutes with ScalaFutures {
  def extensionRoutes(samUser: SamUser, samRequestContext: SamRequestContext): server.Route = reject
  def mockDirectoryDao: DirectoryDAO = directoryDAO
  def mockRegistrationDao: RegistrationDAO = registrationDAO
}

object TestSamRoutes {
  val defaultUserInfo = Generator.genWorkbenchUserGoogle.sample.get

  object SamResourceActionPatterns {
    val readPolicies = ResourceActionPattern("read_policies", "", false)
    val alterPolicies = ResourceActionPattern("alter_policies", "", false)
    val delete = ResourceActionPattern("delete", "", false)

    val adminReadPolicies = ResourceActionPattern("admin_read_policies", "", false)
    val adminAddMember = ResourceActionPattern("admin_add_member", "", false)
    val adminRemoveMember = ResourceActionPattern("admin_remove_member", "", false)

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
        Set(SamResourceActions.alterPolicies, SamResourceActions.readPolicies, SamResourceActions.setPublic)
      ),
      ResourceRole(
        ResourceRoleName("admin"),
        Set(adminReadPolicies, adminAddMember, adminRemoveMember)
      )
    ),
    ResourceRoleName("owner")
  )

  def apply(resourceTypes: Map[ResourceTypeName, ResourceType],
            user: SamUser = defaultUserInfo,
            policyAccessDAO: Option[AccessPolicyDAO] = None,
            maybeDirectoryDAO: Option[MockDirectoryDAO] = None,
            cloudExtensions: Option[CloudExtensions] = None,
            adminEmailDomains: Option[Set[String]] = None
           )(implicit system: ActorSystem, materializer: Materializer, executionContext: ExecutionContext) = {
    val dbRef = TestSupport.dbRef
    val resourceTypesWithAdmin = resourceTypes + (resourceTypeAdmin.name -> resourceTypeAdmin)
    // need to make sure MockDirectoryDAO and MockAccessPolicyDAO share the same groups
    val directoryDAO = maybeDirectoryDAO.getOrElse(new MockDirectoryDAO())
    val registrationDAO = new MockRegistrationDAO()
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val policyDAO = policyAccessDAO.getOrElse(new MockAccessPolicyDAO(Map.empty[ResourceTypeName, ResourceType], directoryDAO))

    val emailDomain = "example.com"
    val policyEvaluatorService = PolicyEvaluatorService(emailDomain, resourceTypesWithAdmin, policyDAO, directoryDAO)
    val cloudXtns = cloudExtensions.getOrElse(NoExtensions)
    val mockResourceService = new ResourceService(
      resourceTypesWithAdmin,
      policyEvaluatorService,
      policyDAO,
      directoryDAO,
      cloudXtns,
      emailDomain,
      allowedAdminEmailDomains = adminEmailDomains.getOrElse(Set.empty)
    )
    val mockUserService = new UserService(directoryDAO, cloudXtns, registrationDAO, Seq.empty, new TosService(directoryDAO, registrationDAO, googleServicesConfig.appsDomain, TestSupport.tosConfig))
    val mockTosService = new TosService(directoryDAO,registrationDAO, emailDomain, TestSupport.tosConfig)
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, policyEvaluatorService, resourceTypesWithAdmin, policyDAO, directoryDAO, cloudXtns, emailDomain)
    TestSupport.runAndWait(mockUserService.createUser(user, samRequestContext))
    val allUsersGroup = TestSupport.runAndWait(cloudXtns.getOrCreateAllUsersGroup(directoryDAO, samRequestContext))
    TestSupport.runAndWait(googleDirectoryDAO.createGroup(allUsersGroup.id.toString, allUsersGroup.email))
    mockResourceService.initResourceTypes(samRequestContext).unsafeRunSync()

    val mockStatusService = new StatusService(directoryDAO, registrationDAO, cloudXtns, dbRef)
    new TestSamRoutes(mockResourceService, policyEvaluatorService, mockUserService, mockStatusService, mockManagedGroupService, user, directoryDAO, registrationDAO, tosService = mockTosService, cloudExtensions = cloudXtns)
  }
}
