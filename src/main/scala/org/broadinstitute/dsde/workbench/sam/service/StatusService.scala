package org.broadinstitute.dsde.workbench.sam.service

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model.{WorkbenchGroupEmail, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO

import scala.concurrent.{ExecutionContext, Future}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.util.health.HealthMonitor.GetCurrentStatus
import org.broadinstitute.dsde.workbench.util.health.Subsystems.{GoogleGroups, OpenDJ, Subsystem}
import org.broadinstitute.dsde.workbench.util.health.{HealthMonitor, StatusCheckResponse, SubsystemStatus}

import scala.concurrent.duration._

class StatusService(val directoryDAO: DirectoryDAO, val googleDirectoryDAO: GoogleDirectoryDAO, initialDelay: FiniteDuration = Duration.Zero, pollInterval: FiniteDuration = 1 minute)(implicit system: ActorSystem, executionContext: ExecutionContext) extends LazyLogging {
  implicit val askTimeout = Timeout(5 seconds)

  private val healthMonitor = system.actorOf(HealthMonitor.props(Set(GoogleGroups, OpenDJ))(checkStatus))
  system.scheduler.schedule(initialDelay, pollInterval, healthMonitor, HealthMonitor.CheckAll)

  def getStatus(): Future[StatusCheckResponse] = (healthMonitor ? GetCurrentStatus).asInstanceOf[Future[StatusCheckResponse]]

  private def checkStatus(): Map[Subsystem, Future[SubsystemStatus]] = {
    val opendjCheck: Future[(SubsystemStatus, Option[WorkbenchGroupEmail])] = checkOpenDJ(UserService.allUsersGroupName)
    Map(
      OpenDJ -> opendjCheck.map(_._1),
      GoogleGroups -> checkGoogleGroups(opendjCheck.map(_._2))
    )
  }

  private def checkOpenDJ(groupToLoad: WorkbenchGroupName): Future[(SubsystemStatus, Option[WorkbenchGroupEmail])] = {
    logger.info("checking opendj connection")
    directoryDAO.loadGroupEmail(groupToLoad).map {
      case groupOption@Some(_) => (HealthMonitor.OkStatus, groupOption)
      case None => (HealthMonitor.failedStatus(s"could not find group $groupToLoad in opendj"), None)
    }
  }

  private def checkGoogleGroups(groupToCheckOption: Future[Option[WorkbenchGroupEmail]]): Future[SubsystemStatus] = {
    groupToCheckOption.recover {
      case _: Throwable => None // failure with opendj, handled elsewhere
    }.flatMap {
      case Some(groupToCheck) =>
        logger.info("checking google group connection")
        googleDirectoryDAO.getGoogleGroup(groupToCheck).map {
          case Some(_) => HealthMonitor.OkStatus
          case None => HealthMonitor.failedStatus(s"could not find group $groupToCheck in google")
        }
      case None => Future.successful(HealthMonitor.UnknownStatus) // didn't get group email out of opendj so can't check google group
    }
  }
}
