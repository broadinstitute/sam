package org.broadinstitute.dsde.workbench.sam.service

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO

import scala.concurrent.{ExecutionContext, Future}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.util.health.HealthMonitor.GetCurrentStatus
import org.broadinstitute.dsde.workbench.util.health.Subsystems.{GoogleGroups, OpenDJ, Subsystem}
import org.broadinstitute.dsde.workbench.util.health.{HealthMonitor, StatusCheckResponse, SubsystemStatus}

import scala.concurrent.duration._

class StatusService(val directoryDAO: DirectoryDAO, val cloudExtensions: CloudExtensions, initialDelay: FiniteDuration = Duration.Zero, pollInterval: FiniteDuration = 1 minute)(implicit system: ActorSystem, executionContext: ExecutionContext) extends LazyLogging {
  implicit val askTimeout = Timeout(5 seconds)

  private val healthMonitor = system.actorOf(HealthMonitor.props(cloudExtensions.allSubSystems + OpenDJ)(checkStatus))
  system.scheduler.schedule(initialDelay, pollInterval, healthMonitor, HealthMonitor.CheckAll)

  def getStatus(): Future[StatusCheckResponse] = (healthMonitor ? GetCurrentStatus).asInstanceOf[Future[StatusCheckResponse]]

  private def checkStatus(): Map[Subsystem, Future[SubsystemStatus]] = {
    cloudExtensions.checkStatus + (OpenDJ -> checkOpenDJ(cloudExtensions.allUsersGroupName))
  }

  private def checkOpenDJ(groupToLoad: WorkbenchGroupName): Future[SubsystemStatus] = {
    logger.info("checking opendj connection")
    directoryDAO.loadGroupEmail(groupToLoad).map {
      case Some(_) => HealthMonitor.OkStatus
      case None => HealthMonitor.failedStatus(s"could not find group $groupToLoad in opendj")
    }
  }
}
