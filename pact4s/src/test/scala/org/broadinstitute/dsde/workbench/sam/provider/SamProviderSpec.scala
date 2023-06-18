package org.broadinstitute.dsde.workbench.sam.provider

import akka.http.scaladsl.Http
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.oauth2.mock.FakeOpenIDConnectConfiguration
import org.broadinstitute.dsde.workbench.sam.Generator.{genResourceType, genWorkspaceResourceType}
import org.broadinstitute.dsde.workbench.sam.MockTestSupport.genSamRoutes
import org.broadinstitute.dsde.workbench.sam.azure.AzureService
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO, StatefulMockAccessPolicyDaoBuilder}
import org.broadinstitute.dsde.workbench.sam.google.GoogleExtensions
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.{Generator, MockSamDependencies, MockTestSupport}
import org.broadinstitute.dsde.workbench.util.health.{StatusCheckResponse, SubsystemStatus, Subsystems}
import org.http4s.headers.Authorization
import org.http4s.{AuthScheme, Credentials}
import org.mockito.Mockito.lenient
import org.mockito.scalatest.MockitoSugar
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import pact4s.provider.ProviderRequestFilter.{NoOpFilter, SetHeaders}
import pact4s.provider.StateManagement.StateManagementFunction
import pact4s.provider._
import pact4s.scalatest.PactVerifier

import java.io.File
import java.lang.Thread.sleep
import java.nio.file.Paths
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object States {
  val SamOK = "Sam is ok"
  val SamNotOK = "Sam is not ok"
  val UserExists = "user exists"
}

class SamProviderSpec
    extends AnyFlatSpec
    with ScalatestRouteTest
    with MockTestSupport
    with BeforeAndAfterAll
    with PactVerifier
    with LazyLogging
    with MockitoSugar {

  // The default login user
  val defaultSamUser: SamUser = Generator.genWorkbenchUserBoth.sample.get.copy(enabled = true)
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(defaultSamUser.id), WorkbenchEmail("all_users@fake.com"))

  // Generate some random Sam resource types.
  // These resource types are injected into ResourceService and PolicyEvaluatorService
  val samResourceTypes = Set(genResourceType.sample.get, genWorkspaceResourceType.sample.get)

  // Minimally viable Sam service and states for consumer verification
  val userService: UserService = TestUserServiceBuilder()
    .withAllUsersGroup(allUsersGroup)
    .withEnabledUser(defaultSamUser)
    .withAllUsersHavingAcceptedTos()
    .build

  val directoryDAO: DirectoryDAO = userService.directoryDAO
  val cloudExtensions: CloudExtensions = userService.cloudExtensions

  // Policy service and states for consumer verification
  val accessPolicyDAO: AccessPolicyDAO = StatefulMockAccessPolicyDaoBuilder()
    .withRandomAccessPolicy(SamResourceTypes.workspaceName, Set(defaultSamUser.id))
    .build
  val policyEvaluatorService: PolicyEvaluatorService =
    TestPolicyEvaluatorServiceBuilder(directoryDAO, accessPolicyDAO).withResourceTypes(samResourceTypes).build
  // lenient()
  //  .doReturn {
  //    print("listUserResources")
  //    IO(true)
  //  }
  //  .when(policyEvaluatorService)
  //  .listUserResources(any[ResourceTypeName], any[WorkbenchUserId], any[SamRequestContext])
  // lenient()
  //  .doReturn {
  //    print("hasPermission")
  //    IO(true)
  //  }
  //  .when(policyEvaluatorService)
  //  .hasPermission(any[FullyQualifiedResourceId], any[ResourceAction], any[WorkbenchUserId], any[SamRequestContext])

  // Resource service and states for consumer verification
  // Here we are injecting a random resource type as well as a workspace resource type.
  // We can also inject all possible Sam resource types by taking a look at genResourceTypeName if needed.
  val resourceService: ResourceService =
    TestResourceServiceBuilder(policyEvaluatorService, accessPolicyDAO, directoryDAO, cloudExtensions)
      .withResourceTypes(samResourceTypes)
      .build

  // The following services are mocked for now
  val googleExt: GoogleExtensions = mock[GoogleExtensions]
  val mockManagedGroupService: ManagedGroupService = mock[ManagedGroupService]
  val tosService: TosService = MockTosServiceBuilder().withAllAccepted().build
  val azureService: AzureService = mock[AzureService]
  val statusService: StatusService = mock[StatusService]

  val nonCriticalSubsystemsStatus = Map(
    Subsystems.GoogleGroups -> SubsystemStatus(ok = true, None),
    Subsystems.GoogleIam -> SubsystemStatus(ok = true, None),
    Subsystems.GooglePubSub -> SubsystemStatus(ok = true, None),
    Subsystems.OpenDJ -> SubsystemStatus(ok = true, None)
  )

  private def criticalSubsystemsStatus(healthy: Boolean) = StatusService.criticalSubsystems.map(key => key -> SubsystemStatus(ok = healthy, None)).toMap

  /** Use in conjunction with StateManagementFunction to mock critical systems status.
    *
    * We are doing this to quickly mock the response shape of /status route without giving much consideration to the side effects of the real getStatus
    * function. Specifically we are stubbing some computation that returns a Future of StatusCheckResponse according to the input parameter `healthy` flag.
    *
    * @param healthy
    *   represent status of critical subsystems
    * @return
    *   a mockito stub representing a Future of Sam status
    */
  private def mockCriticalSubsystemsStatus(healthy: Boolean): IO[Unit] = for {
    _ <- IO(when {
      statusService.getStatus()
    } thenReturn {
      Future.successful(
        StatusCheckResponse(
          ok = healthy,
          criticalSubsystemsStatus(healthy) ++ nonCriticalSubsystemsStatus
        )
      )
    })
  } yield ()

  private def mockResourceActionPermission(action: String, hasPermission: Boolean): IO[Unit] = for {
    _ <- IO(
      lenient()
        .doReturn {
          println(action + " permission mocked to " + hasPermission)
          IO.pure(hasPermission)
        }
        .when(policyEvaluatorService)
        .hasPermission(any[FullyQualifiedResourceId], eqTo(ResourceAction(action)), any[WorkbenchUserId], any[SamRequestContext])
    )
  } yield ()

  private val providerStatesHandler: StateManagementFunction = StateManagementFunction {
    case ProviderState(States.UserExists, _) =>
      logger.debug(States.UserExists)
    case ProviderState(States.SamOK, _) =>
      mockCriticalSubsystemsStatus(true).unsafeRunSync()
    case ProviderState(States.SamNotOK, _) =>
      mockCriticalSubsystemsStatus(false).unsafeRunSync()
    case ProviderState("user has delete permission", _) =>
      mockResourceActionPermission("delete", true).unsafeRunSync()
    case ProviderState("user has write permission", _) =>
      mockResourceActionPermission("write", true).unsafeRunSync()
    case ProviderState("user does not have delete permission", _) =>
      mockResourceActionPermission("delete", false).unsafeRunSync()
    case ProviderState("user does not have write permission", _) =>
      mockResourceActionPermission("write", false).unsafeRunSync()
    case _ =>
      logger.debug("other state")
  }

  def genSamDependencies: MockSamDependencies =
    MockSamDependencies(
      resourceService,
      policyEvaluatorService,
      tosService,
      userService,
      statusService,
      mockManagedGroupService,
      directoryDAO,
      accessPolicyDAO,
      googleExt,
      FakeOpenIDConnectConfiguration,
      azureService
    )

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
    case Some(s) if !s.isBlank => consumerVersionSelectors = consumerVersionSelectors.branch(s, consumerName)
    case _ => consumerVersionSelectors = consumerVersionSelectors.deployedOrReleased.mainBranch
  }

  // The request filter allows the provider to intercept a HTTP request and
  // to introduce some custom logic to filter the request BEFORE processing takes place.
  //
  // Here we implemented a custom filter that allows the provider to intercept an auth header from the HTTP request.
  //    If the consumer request contains an auth header, the 'parseAuth' filtering logic will be applied.
  //    Otherwise no filtering logic is applied.
  //
  // The 'parseAuth' function parses the auth header and can be used to substitute the original token
  // sent by the consumer with a 'real' token to satisfy the api auth requirements.
  //
  def requestFilter: ProviderRequest => ProviderRequestFilter = customFilter

  // Convenient method to reset provider states
  private def reInitializeStates(): Unit = {
    mockCriticalSubsystemsStatus(true).unsafeRunSync()
    mockResourceActionPermission("write", true).unsafeRunSync()
    mockResourceActionPermission("delete", true).unsafeRunSync()
  }

  private def customFilter(req: ProviderRequest): ProviderRequestFilter = {
    println("recv req")
    println(req.uri.getRawPath)
    println(req.uri.getPath)
    req.getFirstHeader("Authorization") match {
      case Some((_, value)) =>
        parseAuth(value)
      case None =>
        logger.debug("no auth header found")
        NoOpFilter
    }
  }

  private def parseAuth(auth: String): ProviderRequestFilter =
    Authorization
      .parse(auth)
      .map {
        case Authorization(Credentials.Token(AuthScheme.Bearer, token)) =>
          var proxyToken = token
          // intended to match a certain class of user token
          token match {
            case "accessToken" =>
              logger.debug("do bearer 'accessToken'")
              // e.g. proxy token that impersonates a regular user
              proxyToken = "user" + token
            case _ =>
              logger.debug("do other bearer token")
              // e.g. proxy token that impersonates a super user
              proxyToken = "su" + token
          }
          SetHeaders("Authorization" -> s"Bearer $proxyToken")
        case _ =>
          logger.debug("do other AuthScheme")
          NoOpFilter
      }
      .getOrElse(NoOpFilter)

  val provider: ProviderInfoBuilder = ProviderInfoBuilder(
    name = "sam-provider",
    pactSource = PactSource.FileSource(
      Map("wds-consumer" -> new File("./src/test/resources/wds-consumer-sam-provider.json"))
    )
    // .PactBrokerWithSelectors(
    //  brokerUrl = pactBrokerUrl
    // )
    // .withConsumerVersionSelectors(consumerVersionSelectors)
    // .withAuth(BasicAuth(pactBrokerUser, pactBrokerPass))
    // .withPendingPactsEnabled(ProviderTags(gitSha))
  ).withHost("localhost")
    .withPort(8080)
    .withRequestFiltering(requestFilter)
    // More sophisticated state management can be done here.
    // It's recommended to have predefined states, e.g.
    // TestUserServiceBuilder, StatefulMockAccessPolicyDaoBuilder,
    // TestPolicyEvaluatorServiceBuilder, and TestResourceServiceBuilder
    // how to verify external states of cloud services through mocking and stubbing
    .withStateManagementFunction(
      providerStatesHandler
        .withBeforeEach(() => reInitializeStates())
    )

  val currentDirectory = Paths.get(".").toAbsolutePath.normalize.toString
  println("currentDirectory")
  println(currentDirectory)

  it should "Verify pacts" in {
    verifyPacts(
      providerBranch = if (branch.isEmpty) None else Some(Branch(branch)),
      publishVerificationResults = None,
      // publishVerificationResults = Some(
      //  PublishVerificationResults(gitSha, ProviderTags(branch))
      // ),
      providerVerificationOptions = Seq(
        ProviderVerificationOption.SHOW_STACKTRACE
      ).toList,
      verificationTimeout = Some(30.seconds)
    )
  }
}
