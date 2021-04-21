package org.broadinstitute.dsde.workbench.sam.service

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.sam.dataAccess.{ConnectionType, DirectoryDAO, RegistrationDAO}
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

  private def checkOpenDJ(): IO[SubsystemStatus] = IO {
    // Since Status calls are ~80% of all Sam calls and are easy to track separately, Status calls are not being traced.
    logger.info("checking opendj connection")
    if (registrationDAO.getConnectionTarget() != ConnectionType.LDAP) {
      HealthMonitor.failedStatus("Connection of RegistrationDAO is not to OpenDJ")
    } else {
      if (registrationDAO.checkStatus(SamRequestContext(None)))
        HealthMonitor.OkStatus
      else
        HealthMonitor.failedStatus(s"LDAP database connection invalid or timed out checking")
    }
  }

  private def checkDatabase(): IO[SubsystemStatus] = IO {
    logger.info("checking database connection")
    if (directoryDAO.getConnectionTarget() != ConnectionType.Postgres) {
      HealthMonitor.failedStatus("Connection of RegistrationDAO is not to Postgres")
    } else {
      if (directoryDAO.checkStatus(SamRequestContext(None)))
        HealthMonitor.OkStatus
      else
        HealthMonitor.failedStatus("Postgres database connection invalid or timed out checking")
    }
  }
}
