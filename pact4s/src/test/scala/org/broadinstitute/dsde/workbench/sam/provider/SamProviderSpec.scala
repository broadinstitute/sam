package org.broadinstitute.dsde.workbench.sam.provider

import akka.http.scaladsl.Http
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.oauth2.mock.FakeOpenIDConnectConfiguration
import org.broadinstitute.dsde.workbench.sam.MockTestSupport.genSamRoutes
import org.broadinstitute.dsde.workbench.sam.azure.AzureService
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, StatefulMockAccessPolicyDaoBuilder}
import org.broadinstitute.dsde.workbench.sam.google.GoogleExtensions
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.{Generator, MockSamDependencies, MockTestSupport}
import org.broadinstitute.dsde.workbench.util.health.{StatusCheckResponse, SubsystemStatus, Subsystems}
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import pact4s.provider.Authentication.BasicAuth
import pact4s.provider.ProviderRequestFilter.{NoOpFilter, SetHeaders}
import pact4s.provider.StateManagement.StateManagementFunction
import pact4s.provider._
import pact4s.scalatest.PactVerifier

import java.lang.Thread.sleep
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class SamProviderSpec
    extends AnyFlatSpec
    with ScalatestRouteTest
    with MockTestSupport
    with BeforeAndAfterAll
    with PactVerifier
    with LazyLogging
    with MockitoSugar {

  val defaultSamUser: SamUser = Generator.genWorkbenchUserBoth.sample.get.copy(enabled=true)
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(defaultSamUser.id), WorkbenchEmail("all_users@fake.com"))
  /*val defaultResourceTypeActionPatterns = Set(
    SamResourceActionPatterns.alterPolicies,
    SamResourceActionPatterns.delete,
    SamResourceActionPatterns.readPolicies,
    ResourceActionPattern("view", "", false),
    ResourceActionPattern("non_owner_action", "", false)
  )
  val defaultResourceTypeActions =
    Set(ResourceAction("alter_policies"), ResourceAction("delete"), ResourceAction("read_policies"), ResourceAction("view"), ResourceAction("non_owner_action"))
  val defaultResourceType = ResourceType(
    ResourceTypeName(UUID.randomUUID().toString),
    defaultResourceTypeActionPatterns,
    Set(
      ResourceRole(ResourceRoleName("owner"), defaultResourceTypeActions - ResourceAction("non_owner_action")),
      ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))
    ),
    ResourceRoleName("owner")
  )
  val otherResourceType = ResourceType(
    ResourceTypeName(UUID.randomUUID().toString),
    defaultResourceTypeActionPatterns,
    Set(
      ResourceRole(ResourceRoleName("owner"), defaultResourceTypeActions - ResourceAction("non_owner_action")),
      ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))
    ),
    ResourceRoleName("owner")
  )
  val workspaceResourceType = ResourceType(
    ResourceTypeName("workspace"),
    defaultResourceTypeActionPatterns,
    Set(
      ResourceRole(ResourceRoleName("owner"), defaultResourceTypeActions - ResourceAction("non_owner_action")),
      ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))
    ),
    ResourceRoleName("owner")
  )*/
  /*val accessPolicy = AccessPolicy(
    FullyQualifiedPolicyId(
      FullyQualifiedResourceId(SamResourceTypes.workspaceName, ResourceId(UUID.randomUUID().toString)),
      AccessPolicyName("member")
    ),
    Set(defaultSamUser.id),
    genNonPetEmail.sample.get,
    Set(),
    Set(),
    Set(),
    false
  )

  val policies: Map[WorkbenchGroupIdentity, WorkbenchGroup] = Map(accessPolicy.id -> accessPolicy)*/

  def genSamDependencies: MockSamDependencies = {
    val userService: UserService = TestUserServiceBuilder()
      .withAllUsersGroup(allUsersGroup)
      .withEnabledUser(defaultSamUser)
      .withAllUsersHavingAcceptedTos()
      .build
    // val directoryDAO: DirectoryDAO = MockDirectoryDaoBuilder(allUsersGroup).build
    val directoryDAO: DirectoryDAO = userService.directoryDAO
    // val cloudExtensions: CloudExtensions = MockCloudExtensionsBuilder(allUsersGroup).build
    val cloudExtensions: CloudExtensions = userService.cloudExtensions
    // val directoryDAO: DirectoryDAO = mock[DirectoryDAO]
    // val cloudExtensions: CloudExtensions = mock[CloudExtensions]
    // val policyDAO = mock[AccessPolicyDAO]
    val policyDAO = StatefulMockAccessPolicyDaoBuilder()
      .withAccessPolicy(SamResourceTypes.workspaceName, Set(defaultSamUser.id))
      .build
    val policyEvaluatorService = TestPolicyEvaluatorServiceBuilder(directoryDAO,
      policyDAOOpt = Some(policyDAO))
      .build

    val googleExt = mock[GoogleExtensions]
    // val policyEvaluatorService = mock[PolicyEvaluatorService]
    // val policyEvaluatorService = PolicyEvaluatorService("example.com", Map(defaultResourceType.name -> defaultResourceType, otherResourceType.name -> otherResourceType, workspaceResourceType.name -> workspaceResourceType), policyDAO, directoryDAO)
    val mockResourceService = mock[ResourceService]
    val mockManagedGroupService = mock[ManagedGroupService]
    // val tosService = mock[TosService] // replaced by MockTosServiceBuilder
    val tosService = MockTosServiceBuilder().withAllAccepted().build
    val azureService = mock[AzureService]
    // val userService: UserService = mock[UserService] // replaced by a mock returned by constructor call
    // val userService: UserService = spy(new UserService(directoryDAO, cloudExtensions, Seq(), tosService))
    val statusService = mock[StatusService]
    when {
      statusService.getStatus()
    } thenReturn {
      Future.successful(
        StatusCheckResponse(
          ok = true,
          Map(
            Subsystems.GoogleGroups -> SubsystemStatus(ok = true, None),
            Subsystems.GoogleIam -> SubsystemStatus(ok = true, None),
            Subsystems.GooglePubSub -> SubsystemStatus(ok = true, None),
            Subsystems.OpenDJ -> SubsystemStatus(ok = true, None)
          )
        )
      )
    }

    // Replaced by stubbing below
    // when {
    //  directoryDAO.loadUserByGoogleSubjectId(any[GoogleSubjectId], any[SamRequestContext])
    // } thenReturn {
    //  val samUser = SamUser(WorkbenchUserId("test"), None, WorkbenchEmail("test@test"), None, enabled = true, None)
    //  IO.pure(Option(samUser))
    // }

    // when(
    //  directoryDAO.loadUserByGoogleSubjectId(any[GoogleSubjectId], any[SamRequestContext])
    //).thenAnswer { (i: InvocationOnMock) =>
    //  val googleSubjectId: Option[GoogleSubjectId] = Some(i.getArgument[GoogleSubjectId](0))
    //  val defaultSamUser: Option[SamUser] = Some(SamUser(WorkbenchUserId("test"), googleSubjectId, WorkbenchEmail("test@test"), None, enabled = true, None))
    //  val samRequestContext = i.getArgument[SamRequestContext](1)
    //  googleSubjectId match {
    //    case Some(g) =>
    //      println(g.value)
    //      println(activeSamUserSubjectId)
    //      println(activeSamUserEmail)
        // directoryDAO.createUser(SamUser(WorkbenchUserId(fakeUserSubjectId.get), googleSubjectId, WorkbenchEmail(fakeUserEmail.get), None, enabled = true, None), samRequestContext)
    //    case _ => println("No googleSubjectId found")
    //  }
    //  var samUser: Option[SamUser] = None
    //  activeSamUserSubjectId match {
    //    case Some(userSubjectId) =>
    //      activeSamUserEmail match {
    //        case Some(userEmail) =>
    //          samUser = Some(SamUser(WorkbenchUserId(userSubjectId), googleSubjectId, WorkbenchEmail(userEmail), None, enabled = true, None))
    //        case _ =>
    //          samUser = defaultSamUser
    //      }
    //    case _ =>
    //      samUser = defaultSamUser
    //  }
    //  IO.pure(samUser)
    //}

      // Wrapped inside MockTosServiceBuilder
    // when {
    //  tosService.getTosComplianceStatus(any[SamUser])
    // } thenReturn IO.pure(TermsOfServiceComplianceStatus(WorkbenchUserId("test"), userHasAcceptedLatestTos = true, permitsSystemUsage = true))

    val fakeWorkspaceResourceType = ResourceType(ResourceTypeName("workspace"), Set.empty, Set.empty, ResourceRoleName("workspace"))
    when {
      mockResourceService.getResourceType(any[ResourceTypeName])
    } thenReturn IO.pure(Option(fakeWorkspaceResourceType))

    // when {
    //  policyEvaluatorService.listUserResources(any[ResourceTypeName], any[WorkbenchUserId], any[SamRequestContext])
    //} thenReturn IO.pure(
    //  Vector(
    //    UserResourcesResponse(
    //      resourceId = ResourceId("cea587e9-9a8e-45b6-b985-9e3803754020"),
    //      direct = RolesAndActions(Set.empty, Set.empty),
    //      inherited = RolesAndActions(Set.empty, Set.empty),
    //      public = RolesAndActions(Set.empty, Set.empty),
    //      authDomainGroups = Set.empty,
    //      missingAuthDomainGroups = Set.empty
    //    )
    //  )
    //)

    MockSamDependencies(
      mockResourceService,
      policyEvaluatorService,
      tosService,
      userService,
      statusService,
      mockManagedGroupService,
      directoryDAO,
      policyDAO,
      googleExt,
      FakeOpenIDConnectConfiguration,
      azureService
    )
  }

  override def beforeAll(): Unit = {
    startSam.unsafeToFuture()
    startSam.start
    sleep(5000)
  }

  def startSam: IO[Http.ServerBinding] =
    for {
      binding <- IO
        .fromFuture(IO(Http().newServerAt("localhost", 8080).bind(genSamRoutes(genSamDependencies, defaultSamUser).route)))
        .onError { t: Throwable =>
          IO(logger.error("FATAL - failure starting http server", t)) *> IO.raiseError(t)
        }
      _ <- IO.fromFuture(IO(binding.whenTerminated))
      _ <- IO(system.terminate())
    } yield binding

  lazy val pactBrokerUrl: String = sys.env.getOrElse("PACT_BROKER_URL", "")
  lazy val pactBrokerUser: String = sys.env.getOrElse("PACT_BROKER_USERNAME", "")
  lazy val pactBrokerPass: String = sys.env.getOrElse("PACT_BROKER_PASSWORD", "")
  // Provider branch, sha
  lazy val branch: String = sys.env.getOrElse("PROVIDER_BRANCH", "")
  lazy val gitSha: String = sys.env.getOrElse("PROVIDER_SHA", "")
  // Consumer name, bran, sha (used for webhook events only)
  lazy val consumerName: Option[String] = sys.env.get("CONSUMER_NAME")
  lazy val consumerBranch: Option[String] = sys.env.get("CONSUMER_BRANCH")
  // This matches the latest commit of the consumer branch that triggered the webhook event
  lazy val consumerSha: Option[String] = sys.env.get("CONSUMER_SHA")

  var consumerVersionSelectors: ConsumerVersionSelectors = ConsumerVersionSelectors()
  // consumerVersionSelectors = consumerVersionSelectors.mainBranch
  // The following match condition basically says
  // 1. If verification is triggered by consumer pact change, verify only the changed pact.
  // 2. For normal Sam PR, verify all consumer pacts in Pact Broker labelled with a deployed environment (alpha, dev, prod, staging).
  consumerBranch match {
    case Some(s) if !s.isBlank() => consumerVersionSelectors = consumerVersionSelectors.branch(s, consumerName)
    case _ => consumerVersionSelectors = consumerVersionSelectors.deployedOrReleased.mainBranch
  }

  // If the auth header in the request is "correct", we can replace it with an auth header that will actually work with our API,
  // else we leave it as is to be rejected.
  def requestFilter: ProviderRequest => ProviderRequestFilter = req =>
    req.getFirstHeader("Authorization") match {
      case Some((_, value)) =>
        Authorization
          .parse(value)
          .map {
            case Authorization(Credentials.Token(AuthScheme.Bearer, token)) =>
              println(s"Captured token ${token}")
              token match {
                case "accessToken" =>
                  println("do accessToken")
                case _ =>
                  println("do others")
              }
              println(s"Set headers")
              SetHeaders("Authorization" -> s"Bearer ${token}")
            case _ =>
              println("AuthScheme is not Bearer")
              NoOpFilter
          }
          .getOrElse(NoOpFilter)
      case None =>
        println("No Authorization header found.")
        NoOpFilter
    }

  val provider: ProviderInfoBuilder = ProviderInfoBuilder(
    name = "sam-provider",
    // pactSource = PactSource.FileSource(Map("leo-consumer" -> new File("src/test/resources/leo-consumer-sam-provider.json")))
    pactSource = PactSource
      .PactBrokerWithSelectors(
        brokerUrl = pactBrokerUrl
      )
      .withConsumerVersionSelectors(consumerVersionSelectors)
      .withAuth(BasicAuth(pactBrokerUser, pactBrokerPass))
  ).withHost("localhost")
    .withPort(8080)
    //.withRequestFiltering(requestFilter)
    .withStateManagementFunction(
      StateManagementFunction {
        case ProviderState("user exists", params) =>
          println("user exists")
        case _ =>
          println("do default")
      }
        .withBeforeEach(() => ())
    )

  it should "Verify pacts" in {
    verifyPacts(
      providerBranch = if (branch.isEmpty) None else Some(Branch(branch)),
      publishVerificationResults = Some(
        PublishVerificationResults(gitSha, ProviderTags(branch))
      ),
      providerVerificationOptions = Seq(
        ProviderVerificationOption.SHOW_STACKTRACE
      ).toList,
      verificationTimeout = Some(30.seconds)
    )
  }
}
