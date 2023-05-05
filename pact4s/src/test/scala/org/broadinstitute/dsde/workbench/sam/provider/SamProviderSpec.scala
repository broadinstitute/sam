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

  val defaultSamUser: SamUser = Generator.genWorkbenchUserBoth.sample.get.copy(enabled = true)
  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(defaultSamUser.id), WorkbenchEmail("all_users@fake.com"))

  def genSamDependencies: MockSamDependencies = {
    val userService: UserService = TestUserServiceBuilder()
      .withAllUsersGroup(allUsersGroup)
      .withEnabledUser(defaultSamUser)
      .withAllUsersHavingAcceptedTos()
      .build

    val directoryDAO: DirectoryDAO = userService.directoryDAO
    val cloudExtensions: CloudExtensions = userService.cloudExtensions
    val policyDAO = StatefulMockAccessPolicyDaoBuilder()
      .withAccessPolicy(SamResourceTypes.workspaceName, Set(defaultSamUser.id))
      .build
    val policyEvaluatorService = TestPolicyEvaluatorServiceBuilder(directoryDAO, policyDAOOpt = Some(policyDAO)).build

    val googleExt = mock[GoogleExtensions]
    val resourceService: ResourceService =
      TestResourceServiceBuilder(policyEvaluatorService, policyDAO, directoryDAO, cloudExtensions).withWorkspaceResourceType().build
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
    // .withRequestFiltering(requestFilter)
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
