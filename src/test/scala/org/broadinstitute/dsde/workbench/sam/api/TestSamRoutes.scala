package org.broadinstitute.dsde.workbench.sam.api

import akka.actor.ActorSystem
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives.reject
import akka.stream.Materializer
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.google.mock.MockGoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.oauth2.mock.FakeOpenIDConnectConfiguration
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.broadinstitute.dsde.workbench.sam.TestSupport.{samRequestContext, tosConfig}
import org.broadinstitute.dsde.workbench.sam.azure.{AzureService, CrlService, MockCrlService}
import org.broadinstitute.dsde.workbench.sam.config.{LiquibaseConfig, TermsOfServiceConfig}
import org.broadinstitute.dsde.workbench.sam.dataAccess._
import org.broadinstitute.dsde.workbench.sam.model.SamResourceActions.{adminAddMember, adminReadPolicies, adminRemoveMember}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.{Generator, TestSupport}
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.ExecutionContext

/** Created by dvoet on 7/14/17.
  */
class TestSamRoutes(
    resourceService: ResourceService,
    policyEvaluatorService: PolicyEvaluatorService,
    userService: UserService,
    statusService: StatusService,
    managedGroupService: ManagedGroupService,
    val user: SamUser,
    val cloudExtensions: CloudExtensions = NoExtensions,
    override val newSamUser: Option[SamUser] = None,
    tosService: TosService,
    override val azureService: Option[AzureService] = None
)(implicit
    override val system: ActorSystem,
    override val materializer: Materializer,
    override val executionContext: ExecutionContext,
    override val openTelemetry: OpenTelemetryMetrics[IO]
) extends SamRoutes(
      resourceService,
      userService,
      statusService,
      managedGroupService,
      TermsOfServiceConfig(true, false, "0", "app.terra.bio/#terms-of-service"),
      policyEvaluatorService,
      tosService,
      LiquibaseConfig("", false),
      FakeOpenIDConnectConfiguration,
      azureService
    )
    with MockSamUserDirectives
    with ExtensionRoutes
    with ScalaFutures {
  def extensionRoutes(samUser: SamUser, samRequestContext: SamRequestContext): server.Route = reject
  def createUserAndAcceptTos(samUser: SamUser, samRequestContext: SamRequestContext): Unit = {
    TestSupport.runAndWait(userService.createUser(samUser, samRequestContext))
    TestSupport.runAndWait(tosService.acceptTosStatus(samUser.id, samRequestContext))
  }
}

class TestSamTosEnabledRoutes(
    resourceService: ResourceService,
    policyEvaluatorService: PolicyEvaluatorService,
    userService: UserService,
    statusService: StatusService,
    managedGroupService: ManagedGroupService,
    val user: SamUser,
    directoryDAO: DirectoryDAO,
    val cloudExtensions: CloudExtensions = NoExtensions,
    override val newSamUser: Option[SamUser] = None,
    tosService: TosService,
    override val azureService: Option[AzureService] = None
)(implicit
    override val system: ActorSystem,
    override val materializer: Materializer,
    override val executionContext: ExecutionContext,
    override val openTelemetry: OpenTelemetryMetrics[IO]
) extends SamRoutes(
      resourceService,
      userService,
      statusService,
      managedGroupService,
      TermsOfServiceConfig(true, false, "0", "app.terra.bio/#terms-of-service"),
      policyEvaluatorService,
      tosService,
      LiquibaseConfig("", false),
      FakeOpenIDConnectConfiguration,
      azureService
    )
    with MockSamUserDirectives
    with ExtensionRoutes
    with ScalaFutures {
  def extensionRoutes(samUser: SamUser, samRequestContext: SamRequestContext): server.Route = reject
  def mockDirectoryDao: DirectoryDAO = directoryDAO
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

  def apply(
      resourceTypes: Map[ResourceTypeName, ResourceType],
      user: SamUser = defaultUserInfo,
      policyAccessDAO: Option[AccessPolicyDAO] = None,
      maybeDirectoryDAO: Option[MockDirectoryDAO] = None,
      cloudExtensions: Option[CloudExtensions] = None,
      adminEmailDomains: Option[Set[String]] = None,
      crlService: Option[CrlService] = None,
      acceptTermsOfService: Boolean = true
  )(implicit system: ActorSystem, materializer: Materializer, executionContext: ExecutionContext, openTelemetry: OpenTelemetryMetrics[IO]) = {
    val dbRef = TestSupport.dbRef
    val resourceTypesWithAdmin = resourceTypes + (resourceTypeAdmin.name -> resourceTypeAdmin)
    // need to make sure MockDirectoryDAO and MockAccessPolicyDAO share the same groups
    val directoryDAO = maybeDirectoryDAO.getOrElse(new MockDirectoryDAO())
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
    val mockTosService = new TosService(directoryDAO, TestSupport.tosConfig)
    val mockUserService = new UserService(directoryDAO, cloudXtns, Seq.empty, mockTosService)
    val mockManagedGroupService =
      new ManagedGroupService(mockResourceService, policyEvaluatorService, resourceTypesWithAdmin, policyDAO, directoryDAO, cloudXtns, emailDomain)
    TestSupport.runAndWait(mockUserService.createUser(user, samRequestContext))

    if (acceptTermsOfService) {
      TestSupport.runAndWait(mockTosService.acceptTosStatus(user.id, samRequestContext))
    }

    val allUsersGroup = TestSupport.runAndWait(cloudXtns.getOrCreateAllUsersGroup(directoryDAO, samRequestContext))
    TestSupport.runAndWait(googleDirectoryDAO.createGroup(allUsersGroup.id.toString, allUsersGroup.email))
    mockResourceService.initResourceTypes(samRequestContext).unsafeRunSync()

    val mockStatusService = new StatusService(directoryDAO, cloudXtns)
    val azureService = new AzureService(crlService.getOrElse(MockCrlService(Option(user))), directoryDAO, new MockAzureManagedResourceGroupDAO)
    val userToTestWith = if (acceptTermsOfService) user.copy(acceptedTosVersion = Some(tosConfig.version)) else user
    new TestSamRoutes(
      mockResourceService,
      policyEvaluatorService,
      mockUserService,
      mockStatusService,
      mockManagedGroupService,
      userToTestWith,
      tosService = mockTosService,
      cloudExtensions = cloudXtns,
      azureService = Some(azureService)
    )
  }
}
