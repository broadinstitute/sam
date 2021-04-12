package org.broadinstitute.dsde.workbench.sam.service

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, RegistrationDAO}
import org.broadinstitute.dsde.workbench.sam.db.DbReference
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.util.health.HealthMonitor.GetCurrentStatus
import org.broadinstitute.dsde.workbench.util.health.Subsystems.{Database, OpenDJ, Subsystem}
import org.broadinstitute.dsde.workbench.util.health.{HealthMonitor, StatusCheckResponse, SubsystemStatus}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class StatusService(
    val directoryDAO: DirectoryDAO,
    val registrationDAO: RegistrationDAO,
    val cloudExtensions: CloudExtensions,
    val dbReference: DbReference,
    initialDelay: FiniteDuration = Duration.Zero,
    pollInterval: FiniteDuration = 1 minute)(implicit system: ActorSystem, executionContext: ExecutionContext)
    extends LazyLogging {
  implicit val askTimeout = Timeout(5 seconds)

  private val healthMonitor = system.actorOf(HealthMonitor.props(cloudExtensions.allSubSystems + OpenDJ)(checkStatus _))
  system.scheduler.scheduleAtFixedRate(initialDelay, pollInterval, healthMonitor, HealthMonitor.CheckAll)

  def getStatus(): Future[StatusCheckResponse] = (healthMonitor ? GetCurrentStatus).asInstanceOf[Future[StatusCheckResponse]]

  private def checkStatus(): Map[Subsystem, Future[SubsystemStatus]] =
    cloudExtensions.checkStatus + (OpenDJ -> checkOpenDJ().unsafeToFuture()) + (Database -> checkDatabase().unsafeToFuture())

  private def checkOpenDJ(): IO[SubsystemStatus] = {
    logger.info("checking opendj connection")
    registrationDAO.checkStatus(SamRequestContext(None)).map { // Since Status calls are ~80% of all Sam calls and are easy to track separately, Status calls are not being traced.
      case true => HealthMonitor.OkStatus
      case false => HealthMonitor.failedStatus(s"Enabled users group returning zero members or failing to return in OpenDJ")
    }
  }

  private def checkDatabase(): IO[SubsystemStatus] = {
    logger.info("checking database connection")
    directoryDAO.checkStatus(SamRequestContext(None)).map {
      case true => HealthMonitor.OkStatus
      case false => HealthMonitor.failedStatus("database connection invalid or timed out checking")
    }
  }
}
