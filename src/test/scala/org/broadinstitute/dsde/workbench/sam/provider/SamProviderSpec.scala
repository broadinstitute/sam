package org.broadinstitute.dsde.workbench.sam.provider

import akka.http.scaladsl.Http
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.model.{GoogleSubjectId, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.oauth2.mock.FakeOpenIDConnectConfiguration
import org.broadinstitute.dsde.workbench.sam.TestSupport.genSamRoutes
import org.broadinstitute.dsde.workbench.sam.azure.AzureService
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, DirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.google.GoogleExtensions
import org.broadinstitute.dsde.workbench.sam.model.{
  ResourceId,
  ResourceRoleName,
  ResourceType,
  ResourceTypeName,
  RolesAndActions,
  SamUser,
  UserResourcesResponse
}
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.{Generator, SamDependencies, TestSupport}
import org.broadinstitute.dsde.workbench.util.health.{StatusCheckResponse, SubsystemStatus, Subsystems}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.mockito.MockitoSugar.mock
import pact4s.provider._
import pact4s.scalatest.PactVerifier

import java.io.File
import java.lang.Thread.sleep
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class SamProviderSpec extends AnyFlatSpec with ScalatestRouteTest with TestSupport with BeforeAndAfterAll with PactVerifier {
  def genSamDependencies = {
    val directoryDAO = mock[DirectoryDAO]
    val policyDAO = mock[AccessPolicyDAO]
    val googleExt = mock[GoogleExtensions]

    val policyEvaluatorService = mock[PolicyEvaluatorService]
    val mockResourceService = mock[ResourceService]
    val mockManagedGroupService = mock[ManagedGroupService]
    val tosService = mock[TosService]
    val azureService = mock[AzureService]
    val userService = mock[UserService]
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
      tosService.isTermsOfServiceStatusAcceptable(any[SamUser])
    } thenReturn true

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

    SamDependencies(
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
      binding <- IO.fromFuture(IO(Http().newServerAt("0.0.0.0", 8080).bind(genSamRoutes(genSamDependencies, Generator.genWorkbenchUserBoth.sample.get).route)))
      _ <- IO.fromFuture(IO(binding.whenTerminated))
      _ <- IO(system.terminate())
    } yield binding

  val provider: ProviderInfoBuilder = ProviderInfoBuilder(
    name = "scalatest-provider",
    pactSource = PactSource.FileSource(
      Map("scalatest-consumer" -> new File("src/test/resources/pact-provider.json"))
    )
  ).withHost("localhost").withPort(8080)

  it should "Verify pacts" in {
    verifyPacts(
      publishVerificationResults = None,
      providerVerificationOptions = Nil,
      verificationTimeout = Some(10.seconds)
    )

  }

}
