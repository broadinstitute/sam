package org.broadinstitute.dsde.workbench.sam.service.StatusServiceSpecs

import akka.actor.ActorSystem
import org.broadinstitute.dsde.workbench.sam.TestSupport
import org.broadinstitute.dsde.workbench.sam.dataAccess.MockDirectoryDaoBuilder
import org.broadinstitute.dsde.workbench.sam.service.{MockCloudExtensionsBuilder, StatusService}
import org.broadinstitute.dsde.workbench.util.health.Subsystems
import org.mockito.IdiomaticMockito
import org.scalatest.Inspectors.forEvery
import org.scalatest.concurrent.Eventually
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, OptionValues}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

class StatusServiceSpecNewAndImproved
    extends AnyFunSpec
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

  describe("Sam Status should be OK when all critical subsystems are OK ") {

    it("and there are no other subsystems") {
      // Arrange
      val directoryDAO = MockDirectoryDaoBuilder().withHealthyDatabase // Database is a critical subsystem
        .build
      val cloudExtensions = MockCloudExtensionsBuilder(directoryDAO).build
      val statusService = new StatusService(directoryDAO, cloudExtensions)

      eventually {
        // Act
        val samStatus = runAndWait(statusService.getStatus())

        // Assert
        samStatus should beOk
        forEvery(StatusService.criticalSubsystems) { criticalSubsystem =>
          criticalSubsystem should beOkIn(samStatus)
        }
      }
    }

    it("and there is 1 non-critical subsystem and it is OK") {
      // Arrange
      val subsystem = Subsystems.GoogleGroups
      val directoryDAO = MockDirectoryDaoBuilder().withHealthyDatabase // Database is a critical subsystem
        .build
      val cloudExtensions = MockCloudExtensionsBuilder(directoryDAO)
        .withHealthySubsystem(subsystem)
        .build
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

    it("and there is 1 non-critical subsystem and it is NOT OK") {
      // Arrange
      val subsystem = Subsystems.GoogleGroups
      val directoryDAO = MockDirectoryDaoBuilder().withHealthyDatabase // Database is a critical subsystem
        .build

      val cloudExtensions = MockCloudExtensionsBuilder(directoryDAO)
        .withUnhealthySubsystem(subsystem, List("Because of...reasons"))
        .build
      val statusService = new StatusService(directoryDAO, cloudExtensions)

      // Because of the Actors and the way statuses are cached, we want to use an 'eventually' here
      eventually {
        // Act
        val samStatus = runAndWait(statusService.getStatus())

        // Assert
        samStatus should beOk
        subsystem shouldNot beOkIn(samStatus)
      }
    }

    it("and there are multiple non-critical subsystems and at least 1 is OK and at least 1 is NOT OK") {
      // Arrange
      val directoryDAO = MockDirectoryDaoBuilder().withHealthyDatabase.build

      val healthyNoncriticalSubsystem = Subsystems.GoogleGroups
      val unhealthyNoncriticalSubsystem = Subsystems.GoogleBuckets
      val cloudExtensions = MockCloudExtensionsBuilder(directoryDAO)
        .withHealthySubsystem(healthyNoncriticalSubsystem)
        .withUnhealthySubsystem(unhealthyNoncriticalSubsystem, List(s"Cuz $unhealthyNoncriticalSubsystem is broke"))
        .build

      val statusService = new StatusService(directoryDAO, cloudExtensions)

      // Because of the Actors and the way statuses are cached, we want to use an 'eventually' here
      eventually {
        // Act
        val samStatus = runAndWait(statusService.getStatus())

        // Assert
        samStatus should beOk

        forEvery(StatusService.criticalSubsystems) { criticalSubsystem =>
          criticalSubsystem should beOkIn(samStatus)
        }

        healthyNoncriticalSubsystem should beOkIn(samStatus)
        unhealthyNoncriticalSubsystem shouldNot beOkIn(samStatus)
      }
    }

    it("and there are multiple non-critical subsystems that are all OK") {
      // Arrange
      val directoryDAO = MockDirectoryDaoBuilder().withHealthyDatabase // Database is a critical subsystem
        .build

      val subsystems: Set[Subsystems.Subsystem] = Set(Subsystems.GoogleGroups, Subsystems.Leonardo, Subsystems.Agora)
      val cloudExtensionsBuilder = MockCloudExtensionsBuilder(directoryDAO)
      subsystems.foreach { subsystem =>
        cloudExtensionsBuilder.withHealthySubsystem(subsystem)
      }
      val cloudExtensions = cloudExtensionsBuilder.build

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

    it("and there are multiple non-critical subsystems that are all NOT OK") {
      // Arrange
      val directoryDAO = MockDirectoryDaoBuilder().withHealthyDatabase.build

      val nonCriticalSubsystems: Set[Subsystems.Subsystem] = Set(Subsystems.GoogleGroups, Subsystems.GoogleBuckets)
      val cloudExtensionsBuilder = MockCloudExtensionsBuilder(directoryDAO)
      nonCriticalSubsystems.foreach { subsystem =>
        cloudExtensionsBuilder.withUnhealthySubsystem(subsystem, List(s"Cuz $subsystem is broke"))
      }
      val cloudExtensions = cloudExtensionsBuilder.build

      val statusService = new StatusService(directoryDAO, cloudExtensions)

      // Because of the Actors and the way statuses are cached, we want to use an 'eventually' here
      eventually {
        // Act
        val samStatus = runAndWait(statusService.getStatus())

        // Assert
        samStatus should beOk

        forEvery(StatusService.criticalSubsystems) { criticalSubsystem =>
          criticalSubsystem should beOkIn(samStatus)
        }

        forEvery(nonCriticalSubsystems) { nonCriticalSubsystem =>
          nonCriticalSubsystem shouldNot beOkIn(samStatus)
        }
      }
    }
  }

  describe("Sam Status should NOT be OK") {
    it("when at least 1 critical subsystem is NOT OK and all non-critical subsystems are OK") {
      // Arrange
      val directoryDAO = MockDirectoryDaoBuilder().withUnhealthyDatabase // Database is a critical subsystem
        .build

      val nonCriticalSubsystems: Set[Subsystems.Subsystem] = Set(Subsystems.GoogleGroups, Subsystems.Leonardo, Subsystems.Agora)
      val cloudExtensionsBuilder = MockCloudExtensionsBuilder(directoryDAO)
      nonCriticalSubsystems.foreach { subsystem =>
        cloudExtensionsBuilder.withHealthySubsystem(subsystem)
      }
      val cloudExtensions = cloudExtensionsBuilder.build

      val statusService = new StatusService(directoryDAO, cloudExtensions)

      // Because of the Actors and the way statuses are cached, we want to use an 'eventually' here
      eventually {
        // Act
        val samStatus = runAndWait(statusService.getStatus())

        // Assert
        samStatus shouldNot beOk
        forEvery(StatusService.criticalSubsystems) { criticalSubsystem =>
          criticalSubsystem shouldNot beOkIn(samStatus)
        }

        forEvery(nonCriticalSubsystems) { nonCriticalSubsystem =>
          nonCriticalSubsystem should beOkIn(samStatus)
        }
      }
    }
  }
}
