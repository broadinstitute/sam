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
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}

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
  var binding: IO[Future[Http.ServerBinding]] = _

  def genSamDependencies: MockSamDependencies = {
    // Minimally viable Sam service and states for consumer verification
    val userService: UserService = TestUserServiceBuilder()
      .withAllUsersGroup(allUsersGroup)
      .withEnabledUser(defaultSamUser)
      .withAllUsersHavingAcceptedTos()
      .build

    val directoryDAO: DirectoryDAO = userService.directoryDAO
    val cloudExtensions: CloudExtensions = userService.cloudExtensions

    // Policy service and states for consumer verification
    val accessPolicyDAO = StatefulMockAccessPolicyDaoBuilder()
      .withRandomAccessPolicy(SamResourceTypes.workspaceName, Set(defaultSamUser.id))
      .build
    val policyEvaluatorService = TestPolicyEvaluatorServiceBuilder(directoryDAO, accessPolicyDAO).build

    // Resource service and states for consumer verification
    // Here we are injecting a random resource type as well as a workspace resource type.
    // We can also inject all possible Sam resource types by taking a look at genResourceTypeName if needed.
    val resourceService: ResourceService =
      TestResourceServiceBuilder(policyEvaluatorService, accessPolicyDAO, directoryDAO, cloudExtensions)
        .withResourceTypes(Set(genResourceType.sample.get, genWorkspaceResourceType.sample.get))
        .build

    // The following services are mocked for now
    val googleExt = mock[GoogleExtensions]
    val mockManagedGroupService = mock[ManagedGroupService]
    val tosService = MockTosServiceBuilder().withAllAccepted().build
    val azureService = mock[AzureService]
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
  }

  override def beforeAll(): Unit = {
    println("Initial bind")
    startSam.unsafeToFuture()
    startSam.start
    println("Initial bind suceeded")
    sleep(5000)
  }

  def startSam: IO[Http.ServerBinding] = {
    binding = IO(Http().newServerAt("localhost", 8080).bind(genSamRoutes(genSamDependencies, defaultSamUser).route))
    for {
      binding <- IO
        .fromFuture(binding)
        .onError { t: Throwable =>
          IO(logger.error("FATAL - failure starting http server", t)) *> IO.raiseError(t)
        }
      _ <- IO.fromFuture(IO(binding.whenTerminated))
      _ <- IO(system.terminate())
    } yield binding
  }

  def restartSam(binding: IO[Future[Http.ServerBinding]], atMost: Duration, hardDeadline: FiniteDuration): IO[Http.ServerBinding] = {
    // val onceAllConnectionsTerminated: Future[Http.HttpTerminated] =
    //  Await
    //    .result(binding, atMost)
    //    .terminate(hardDeadline = hardDeadline)

    // onceAllConnectionsTerminated.flatMap { _ =>
    //  system.terminate()
    // }
    system.terminate()
    startSam
  }

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

  private def customFilter(req: ProviderRequest): ProviderRequestFilter =
    req.getFirstHeader("Authorization") match {
      case Some((_, value)) =>
        parseAuth(value)
      case None =>
        logger.debug("no auth header found")
        NoOpFilter
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
    pactSource = PactSource
      .PactBrokerWithSelectors(
        brokerUrl = pactBrokerUrl
      )
      .withConsumerVersionSelectors(consumerVersionSelectors)
      .withAuth(BasicAuth(pactBrokerUser, pactBrokerPass))
      .withPendingPactsEnabled(ProviderTags(gitSha))
  ).withHost("localhost")
    .withPort(8080)
    // .withRequestFiltering(requestFilter)
    // More sophisticated state management can be done here.
    // It's recommended to have predefined states, e.g.
    // TestUserServiceBuilder, StatefulMockAccessPolicyDaoBuilder,
    // TestPolicyEvaluatorServiceBuilder, and TestResourceServiceBuilder
    // how to verify external states of cloud services through mocking and stubbing
    .withStateManagementFunction(
      StateManagementFunction {
        case ProviderState("user exists", params) =>
          logger.debug("user exists")
        case ProviderState("Sam is not ok", params) =>
          println("Detected Sam is not ok state")
          val statusService = mock[StatusService]
          when {
            statusService.getStatus()
          } thenReturn {
            Future.successful(
              StatusCheckResponse(
                ok = false,
                Map()
              )
            )
          }
          genSamDependencies.statusService = statusService
          restartSam(binding, 10.seconds, 3.seconds)
        case ProviderState("Sam is ok", params) =>
          println("Detected Sam is ok state")
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
          genSamDependencies.statusService = statusService
          restartSam(binding, 10.seconds, 3.seconds)
        case _ =>
          logger.debug("other state")
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
