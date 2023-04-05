package org.broadinstitute.dsde.workbench.sam.service

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.util.health.HealthMonitor.GetCurrentStatus
import org.broadinstitute.dsde.workbench.util.health.Subsystems.{Database, Subsystem}
import org.broadinstitute.dsde.workbench.util.health.{HealthMonitor, StatusCheckResponse, SubsystemStatus, Subsystems}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class StatusService(
    directoryDAO: DirectoryDAO,
    cloudExtensions: CloudExtensions,
    initialDelay: FiniteDuration = Duration.Zero,
    pollInterval: FiniteDuration = 1 minute
)(implicit system: ActorSystem, executionContext: ExecutionContext)
    extends LazyLogging {
  implicit val askTimeout = Timeout(5 seconds)

  private val healthMonitor = system.actorOf(HealthMonitor.props(cloudExtensions.allSubSystems)(checkStatus _))
  system.scheduler.scheduleAtFixedRate(initialDelay, pollInterval, healthMonitor, HealthMonitor.CheckAll)

  def getStatus(): Future[StatusCheckResponse] =
    (healthMonitor ? GetCurrentStatus).mapTo[StatusCheckResponse].map { statusCheckResponse =>
      // Sam can still report OK if non-critical systems are not OK
      val overallSamStatus: Boolean = StatusService.criticalSubsystems.forall { subsystem =>
        statusCheckResponse.systems.get(subsystem).exists(_.ok)
      }

      statusCheckResponse.copy(ok = overallSamStatus)
    }

  private def checkStatus(): Map[Subsystem, Future[SubsystemStatus]] =
    cloudExtensions.checkStatus + (Database -> checkDatabase().unsafeToFuture())

  private def checkDatabase(): IO[SubsystemStatus] = IO {
    logger.info("checking database connection")
    if (directoryDAO.checkStatus(SamRequestContext()))
      HealthMonitor.OkStatus
    else
      HealthMonitor.failedStatus("Postgres database connection invalid or timed out checking")
  }
}

object StatusService {
  val criticalSubsystems: Set[Subsystem] = Set(Subsystems.Database)
}
