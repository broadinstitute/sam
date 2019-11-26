package org.broadinstitute.dsde.workbench.sam.service

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.WorkbenchGroupName
import org.broadinstitute.dsde.workbench.sam.directory.DirectoryDAO
import org.broadinstitute.dsde.workbench.util.health.HealthMonitor.GetCurrentStatus
import org.broadinstitute.dsde.workbench.util.health.Subsystems.{OpenDJ, Subsystem}
import org.broadinstitute.dsde.workbench.util.health.{HealthMonitor, StatusCheckResponse, SubsystemStatus}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

trait StatusService extends LazyLogging {
  val directoryDAO: DirectoryDAO
  val cloudExtensions: CloudExtensions
  val initialDelay: FiniteDuration
  val pollInterval: FiniteDuration
  implicit val system: ActorSystem
  implicit val executionContext: ExecutionContext

  implicit val askTimeout = Timeout(5 seconds)

  private val healthMonitor = system.actorOf(HealthMonitor.props(cloudExtensions.allSubSystems + OpenDJ)(checkStatus _))
  system.scheduler.schedule(initialDelay, pollInterval, healthMonitor, HealthMonitor.CheckAll)

  def getStatus(): Future[StatusCheckResponse] = (healthMonitor ? GetCurrentStatus).asInstanceOf[Future[StatusCheckResponse]]

  private def checkStatus(): Map[Subsystem, Future[SubsystemStatus]] =
    cloudExtensions.checkStatus + (OpenDJ -> checkOpenDJ(cloudExtensions.allUsersGroupName).unsafeToFuture())

  private def checkOpenDJ(groupToLoad: WorkbenchGroupName): IO[SubsystemStatus] = {
    logger.info("checking opendj connection")
    directoryDAO.loadGroupEmail(groupToLoad).map {
      case Some(_) => HealthMonitor.OkStatus
      case None => HealthMonitor.failedStatus(s"could not find group $groupToLoad in opendj")
    }
  }
}

object StatusService {
  def apply(directoryDAO: DirectoryDAO,
            cloudExtensions: CloudExtensions,
            initialDelay: FiniteDuration = Duration.Zero,
            pollInterval: FiniteDuration = 1 minute)
           (implicit system: ActorSystem, executionContext: ExecutionContext): StatusService = {
    new StatusServiceImpl(directoryDAO, cloudExtensions, initialDelay, pollInterval)
  }
}

class StatusServiceImpl(val directoryDAO: DirectoryDAO,
                        val cloudExtensions: CloudExtensions,
                        val initialDelay: FiniteDuration = Duration.Zero,
                        val pollInterval: FiniteDuration = 1 minute)
                       (implicit val system: ActorSystem, val executionContext: ExecutionContext) extends StatusService