package org.broadinstitute.dsde.workbench.sam.service.StatusServiceSpecs

import akka.actor.ActorSystem
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.dataAccess.MockDirectoryDaoBuilder
import org.broadinstitute.dsde.workbench.sam.service.{MockCloudExtensionsBuilder, StatusService}
import org.broadinstitute.dsde.workbench.util.health.{SubsystemStatus, Subsystems}
import org.mockito.IdiomaticMockito
import org.scalatest.Inspectors.forEvery
import org.scalatest.concurrent.Eventually
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, OptionValues}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}

class StatusServiceSpecNewAndImproved extends AnyFunSpec
  with Matchers
  with TestSupport
  with IdiomaticMockito
  with OptionValues
  with Eventually
  with BeforeAndAfterAll
  with StatusServiceMatchers {
  implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global
  implicit val system = ActorSystem("StatusServiceSpec")
  implicit override val patienceConfig = PatienceConfig(timeout = 1 second)

  override def afterAll(): Unit = {
    system.terminate()
    super.afterAll()
  }

  describe("Sam Status should be OK") {

    it("when there are no subsystems") {
      // Arrange
      implicit val system = ActorSystem("StatusServiceSpec")
      val directoryDAO = MockDirectoryDaoBuilder().build
      val cloudExtensions = MockCloudExtensionsBuilder(directoryDAO).build
      val statusService = new StatusService(directoryDAO, cloudExtensions)

      eventually {
        // Act
        val samStatus = runAndWait(statusService.getStatus())

        // Assert
        samStatus should beOk
        samStatus.systems shouldBe empty
      }
    }

    it("when there is 1 subsystem that is OK") {
      // Arrange
      val subsystem = Subsystems.GoogleGroups
      val directoryDAO = MockDirectoryDaoBuilder().build
      val cloudExtensions = MockCloudExtensionsBuilder(directoryDAO).build
      cloudExtensions.allSubSystems returns Set(subsystem)
      cloudExtensions.checkStatus returns Map(subsystem -> Future.successful(SubsystemStatus(true, None)))
      val statusService = new StatusService(directoryDAO, cloudExtensions)

      // Because of the Actors and the way statuses are cached, we want to use an 'eventually' here
      eventually {
        // Act
        val samStatus = runAndWait(statusService.getStatus())

        // Assert
        samStatus should beOk
        subsystem should beOkIn(samStatus)
      }
    }

    it("when there is more than 1 subsystem and they are all OK") {
      // Arrange
      val subsystems: Set[Subsystems.Subsystem] = Set(Subsystems.GoogleGroups, Subsystems.Leonardo, Subsystems.Agora)
      val directoryDAO = MockDirectoryDaoBuilder().build
      val cloudExtensions = MockCloudExtensionsBuilder(directoryDAO).build
      cloudExtensions.allSubSystems returns subsystems
      cloudExtensions.checkStatus returns subsystems.map(_ -> Future.successful(SubsystemStatus(true, None))).toMap
      val statusService = new StatusService(directoryDAO, cloudExtensions)

      // Because of the Actors and the way statuses are cached, we want to use an 'eventually' here
      eventually {
        // Act
        val samStatus = runAndWait(statusService.getStatus())

        // Assert
        samStatus should beOk
        forEvery(subsystems) { subsystem =>
          subsystem should beOkIn(samStatus)
        }
      }
    }
  }

  describe("Sam Status should NOT be OK") {
    it("when the Database system is NOT OK") {
      // Arrange
      val subsystem = Subsystems.Database
      val directoryDAO = MockDirectoryDaoBuilder().build
      val cloudExtensions = MockCloudExtensionsBuilder(directoryDAO).build
      val statusService = new StatusService(directoryDAO, cloudExtensions)

      // Because of the Actors and the way statuses are cached, we want to use an 'eventually' here
      eventually {
        // Act
        val samStatus = runAndWait(statusService.getStatus())

        // Assert
        samStatus should beOk
        Subsystems.Database should beOkIn(samStatus)
      }
    }
  }
}
