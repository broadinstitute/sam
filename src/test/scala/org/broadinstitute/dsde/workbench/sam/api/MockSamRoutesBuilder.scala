package org.broadinstitute.dsde.workbench.sam.api

import akka.actor.ActorSystem
import akka.stream.Materializer
import cats.effect.IO
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.broadinstitute.dsde.workbench.sam.azure.AzureService
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.broadinstitute.dsde.workbench.sam.service.{CloudExtensions, ManagedGroupService, PolicyEvaluatorService, ResourceService, StatusService, TosService, UserService}
import org.mockito.MockitoSugar.mock

import scala.concurrent.ExecutionContext.Implicits.global

class MockSamRoutesBuilder {
  implicit val system: ActorSystem = mock[ActorSystem]
  implicit val materializer: Materializer = mock[Materializer]
  implicit val openTelemetry: OpenTelemetryMetrics[IO] = mock[OpenTelemetryMetrics[IO]]
  val mockSamRoutes = mock[SamRoutes]

  val resourceService: ResourceService = mock[ResourceService]
  val policyEvaluatorService: PolicyEvaluatorService = mock[PolicyEvaluatorService]
  val userService: UserService = mock[UserService]
  val statusService: StatusService = mock[StatusService]
  val managedGroupService: ManagedGroupService = mock[ManagedGroupService]
  val user: SamUser = mock[SamUser]
  val directoryDAO: DirectoryDAO = mock[DirectoryDAO]
  val cloudExtensions: CloudExtensions = mock[CloudExtensions]
  val newSamUser: Option[SamUser] = None
  val tosService: TosService = mock[TosService]
  val azureService: Option[AzureService] = None

  def build: SamRoutes = {
    new TestSamRoutes(
      resourceService,
      policyEvaluatorService,
      userService,
      statusService,
      managedGroupService,
      user,
      directoryDAO,
      cloudExtensions,
      newSamUser,
      tosService,
      azureService
    )
  }
}