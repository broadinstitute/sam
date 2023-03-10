package org.broadinstitute.dsde.workbench.sam.service

import akka.actor.ActorSystem
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.dataAccess.MockDirectoryDaoBuilder
import org.broadinstitute.dsde.workbench.util.health.{SubsystemStatus, Subsystems}
import org.mockito.IdiomaticMockito
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.{ExecutionContextExecutor, Future}

class StatusServiceSpecNewAndImproved extends AnyFunSpec with Matchers with TestSupport with IdiomaticMockito {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
  implicit val actorSystem = ActorSystem("StatusServiceSpec")

  describe("Sam Status should be OK") {

    it("when there are no subsystems") {
      // Arrange
      val directoryDAO = MockDirectoryDaoBuilder().build
      val cloudExtensions = MockCloudExtensionsBuilder(directoryDAO).build
      cloudExtensions.allSubSystems returns Set.empty
      cloudExtensions.checkStatus returns Map.empty
      val statusService = new StatusService(directoryDAO, cloudExtensions)

      // Act
      val samStatus = runAndWait(statusService.getStatus())

      // Assert
      samStatus.ok shouldBe true
      samStatus.systems shouldBe empty
    }

    it("when there is 1 subsystem that is OK") {
      // Arrange
      val directoryDAO = MockDirectoryDaoBuilder().build
      val cloudExtensions = MockCloudExtensionsBuilder(directoryDAO).build
      cloudExtensions.allSubSystems returns Set(Subsystems.GoogleGroups)
      cloudExtensions.checkStatus returns Map(Subsystems.GoogleGroups -> Future.successful(SubsystemStatus(true, None)))
      val statusService = new StatusService(directoryDAO, cloudExtensions)

      // Act
      val samStatus = runAndWait(statusService.getStatus())

      // Assert
      samStatus.ok shouldBe true
      samStatus.systems shouldBe empty
    }
  }
}
