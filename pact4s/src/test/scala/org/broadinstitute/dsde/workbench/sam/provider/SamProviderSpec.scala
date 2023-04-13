package org.broadinstitute.dsde.workbench.sam.provider

import akka.http.scaladsl.Http
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.{GoogleSubjectId, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.oauth2.mock.FakeOpenIDConnectConfiguration
import org.broadinstitute.dsde.workbench.sam.MockTestSupport.genSamRoutes
import org.broadinstitute.dsde.workbench.sam.azure.AzureService
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.google.GoogleExtensions
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.{Generator, MockSamDependencies, MockTestSupport}
import org.broadinstitute.dsde.workbench.util.health.{StatusCheckResponse, SubsystemStatus, Subsystems}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import pact4s.provider.Authentication.BasicAuth
import pact4s.provider.StateManagement.StateManagementFunction
import pact4s.provider._
import pact4s.scalatest.PactVerifier

import java.lang.Thread.sleep
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class SamProviderSpec extends AnyFlatSpec with ScalatestRouteTest with MockTestSupport with BeforeAndAfterAll with PactVerifier with LazyLogging with MockitoSugar {
  def genSamDependencies: MockSamDependencies = {
    val directoryDAO = mock[DirectoryDAO]
    val policyDAO = mock[AccessPolicyDAO]
    val googleExt = mock[GoogleExtensions]

    val policyEvaluatorService = mock[PolicyEvaluatorService]
    val mockResourceService = mock[ResourceService]
    val mockManagedGroupService = mock[ManagedGroupService]
    val tosService = mock[TosService]
    val azureService = mock[AzureService]
    val userService = mock[UserService]
    // TODO: This mock currently worked for BPM contract.
    // As BPM evolves or other consumers come into play
    // we will need proper state handling.
    when {
      userService.getUserStatusInfo(any[SamUser], any[SamRequestContext])
    } thenReturn {
      val userStatusInfo = UserStatusInfo("userSubjectId", "userEmail", true, false)
      IO.pure(userStatusInfo)
    }
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

    when {
      directoryDAO.loadUserByGoogleSubjectId(any[GoogleSubjectId], any[SamRequestContext])
    } thenReturn {
      val samUser = SamUser(WorkbenchUserId("test"), None, WorkbenchEmail("test@test"), None, enabled = true, None)
      IO.pure(Option(samUser))
    }

    when {
      tosService.getTosComplianceStatus(any[SamUser])
    } thenReturn IO.pure(TermsOfServiceComplianceStatus(WorkbenchUserId("test"), userHasAcceptedLatestTos = true, permitsSystemUsage = true))

    val fakeWorkspaceResourceType = ResourceType(ResourceTypeName("workspace"), Set.empty, Set.empty, ResourceRoleName("workspace"))
    when {
      mockResourceService.getResourceType(any[ResourceTypeName])
    } thenReturn IO.pure(Option(fakeWorkspaceResourceType))

    when {
      policyEvaluatorService.listUserResources(any[ResourceTypeName], any[WorkbenchUserId], any[SamRequestContext])
    } thenReturn IO.pure(
      Vector(
        UserResourcesResponse(
          resourceId = ResourceId("cea587e9-9a8e-45b6-b985-9e3803754020"),
          direct = RolesAndActions(Set.empty, Set.empty),
          inherited = RolesAndActions(Set.empty, Set.empty),
          public = RolesAndActions(Set.empty, Set.empty),
          authDomainGroups = Set.empty,
          missingAuthDomainGroups = Set.empty
        )
      )
    )

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
        .fromFuture(IO(Http().newServerAt("localhost", 8080).bind(genSamRoutes(genSamDependencies, Generator.genWorkbenchUserBoth.sample.get).route)))
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

  override def provider: ProviderInfoBuilder = ProviderInfoBuilder(
    name = "sam-provider",
    // pactSource = PactSource.FileSource(Map("leo-consumer" -> new File("src/test/resources/leo-consumer-sam-provider.json")))
    pactSource = PactSource
      .PactBrokerWithSelectors(
        brokerUrl = pactBrokerUrl
      )
      .withConsumerVersionSelectors(consumerVersionSelectors)
      .withAuth(BasicAuth(pactBrokerUser, pactBrokerPass))
  ).withHost("localhost").withPort(8080)
    .withStateManagementFunction(
      StateManagementFunction {
        case ProviderState("user exists", params) => {
          val userSubjectId: Option[String] = params.get("userSubjectId")
          val userEmail: Option[String] = params.get("userEmail")
          val enabled: Option[Boolean] = params.get("enabled").map(_.toBoolean)
          println("user exists provider state")
        }
        case _                                    => () // Nothing to do
      }
    )

  it should "Verify pacts" in {
    verifyPacts(
      providerBranch = if (branch.isEmpty) None else Some(Branch(branch)),
      publishVerificationResults = Some(
        PublishVerificationResults(gitSha, ProviderTags(branch))
      ),
      providerVerificationOptions = Seq(
        ProviderVerificationOption.SHOW_STACKTRACE,
      ).toList,
      verificationTimeout = Some(30.seconds)
    )
  }
}
