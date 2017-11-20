package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.SupervisorStrategy.{Escalate, Stop}
import akka.actor._
import akka.pattern._
import akka.http.scaladsl.model.StatusCodes
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.GooglePubSubDAO
import org.broadinstitute.dsde.workbench.google.GooglePubSubDAO.PubSubMessage
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.ResourceAndPolicyName
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.util.FutureSupport
import spray.json._

import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure, Try}

/**
 * Created by dvoet on 12/6/16.
 */
object GoogleGroupSyncMonitorSupervisor {
  sealed trait GoogleGroupSyncMonitorSupervisorMessage
  case object Init extends GoogleGroupSyncMonitorSupervisorMessage
  case object Start extends GoogleGroupSyncMonitorSupervisorMessage

  def props(pollInterval: FiniteDuration, pollIntervalJitter: FiniteDuration, pubSubDao: GooglePubSubDAO, pubSubTopicName: String, pubSubSubscriptionName: String, workerCount: Int, googleExtensions: GoogleExtensions)(implicit executionContext: ExecutionContext): Props = {
    Props(new GoogleGroupSyncMonitorSupervisor(pollInterval, pollIntervalJitter, pubSubDao, pubSubTopicName, pubSubSubscriptionName, workerCount, googleExtensions))
  }
}

class GoogleGroupSyncMonitorSupervisor(val pollInterval: FiniteDuration, pollIntervalJitter: FiniteDuration, pubSubDao: GooglePubSubDAO, pubSubTopicName: String, pubSubSubscriptionName: String, workerCount: Int, googleExtensions: GoogleExtensions)(implicit executionContext: ExecutionContext) extends Actor with LazyLogging {
  import GoogleGroupSyncMonitorSupervisor._
  import context._

  self ! Init

  override def receive = {
    case Init => init pipeTo self
    case Start => for(i <- 1 to workerCount) startOne()
    case Status.Failure(t) => logger.error("error initializing google group sync monitor", t)
  }

  def init = {
    for {
      _ <- pubSubDao.createTopic(pubSubTopicName)
      _ <- pubSubDao.createSubscription(pubSubTopicName, pubSubSubscriptionName)
    } yield Start
  }

  def startOne(): Unit = {
    logger.info("starting GoogleGroupSyncMonitorActor")
    actorOf(GoogleGroupSyncMonitor.props(pollInterval, pollIntervalJitter, pubSubDao, pubSubSubscriptionName, googleExtensions))
  }

  override val supervisorStrategy =
    OneForOneStrategy() {
      case e => {
        logger.error("unexpected error in google group sync monitor", e)
        // start one to replace the error, stop the errored child so that we also drop its mailbox (i.e. restart not good enough)
        startOne()
        Stop
      }
    }

}

object GoogleGroupSyncMonitor {
  case object StartMonitorPass

  def props(pollInterval: FiniteDuration, pollIntervalJitter: FiniteDuration, pubSubDao: GooglePubSubDAO, pubSubSubscriptionName: String, googleExtensions: GoogleExtensions)(implicit executionContext: ExecutionContext): Props = {
    Props(new GoogleGroupSyncMonitorActor(pollInterval, pollIntervalJitter, pubSubDao, pubSubSubscriptionName, googleExtensions))
  }
}

class GoogleGroupSyncMonitorActor(val pollInterval: FiniteDuration, pollIntervalJitter: FiniteDuration, pubSubDao: GooglePubSubDAO, pubSubSubscriptionName: String, googleExtensions: GoogleExtensions)(implicit executionContext: ExecutionContext) extends Actor with LazyLogging with FutureSupport {
  import GoogleGroupSyncMonitor._
  import context._

  self ! StartMonitorPass

  // fail safe in case this actor is idle too long but not too fast (1 second lower limit)
  setReceiveTimeout(max((pollInterval + pollIntervalJitter) * 10, 1 second))

  private def max(durations: FiniteDuration*): FiniteDuration = durations.max

  override def receive = {
    case StartMonitorPass =>
      // start the process by pulling a message and sending it back to self
      pubSubDao.pullMessages(pubSubSubscriptionName, 1).map(_.headOption) pipeTo self

    case Some(message: PubSubMessage) =>
      logger.debug(s"received sync message: $message")

      val groupId: WorkbenchGroupIdentity = parseMessage(message)

      googleExtensions.synchronizeGroupMembers(groupId).toTry.map(sr => (sr, message.ackId)) pipeTo self

    case None =>
      // there was no message to wait and try again
      val nextTime = org.broadinstitute.dsde.workbench.util.addJitter(pollInterval, pollIntervalJitter)
      system.scheduler.scheduleOnce(nextTime.asInstanceOf[FiniteDuration], self, StartMonitorPass)

    case (Success(report: Map[WorkbenchGroupEmail, Seq[SyncReportItem]]), ackId: String) =>
      val errorReports = report.values.flatten.collect {
        case SyncReportItem(_, _, errorReports) if errorReports.nonEmpty => errorReports
      }.flatten

      if (errorReports.isEmpty) {
        // sync done, log it and try again immediately
        acknowledgeMessage(ackId).map(_ => StartMonitorPass) pipeTo self

        import DefaultJsonProtocol._
        import org.broadinstitute.dsde.workbench.sam.google.GoogleModelJsonSupport._
        import WorkbenchIdentityJsonSupport._
        logger.info(s"synchronized google group ${report.toJson.compactPrint}")
      } else {
        throw new WorkbenchExceptionWithErrorReport(ErrorReport("error(s) syncing google group", errorReports.toSeq))
      }

    case (Failure(t), ackId: String) =>
      t match {
        case groupNotFound: WorkbenchExceptionWithErrorReport if groupNotFound.errorReport.statusCode == Some(StatusCodes.NotFound) =>
          // this can happen if a group is created then removed before the sync message is handled
          // acknowledge it so we don't have to handle it again
          acknowledgeMessage(ackId).map(_ => StartMonitorPass) pipeTo self
          logger.info(s"group to synchronize not found: ${groupNotFound.errorReport}")

        case regrets: Throwable => throw regrets
      }

    case Status.Failure(t) => throw t

    case ReceiveTimeout =>
      throw new WorkbenchException("GoogleGroupSyncMonitorActor has received no messages for too long")

    case x => logger.info(s"unhandled $x")
  }

  private def acknowledgeMessage(ackId: String) = {
    pubSubDao.acknowledgeMessagesById(pubSubSubscriptionName, Seq(ackId))
  }

  private def parseMessage(message: PubSubMessage) = {
    (Try {
      import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport.ResourceAndPolicyNameFormat
      message.contents.parseJson.convertTo[ResourceAndPolicyName]
    } recover {
      case _: DeserializationException =>
        import WorkbenchIdentityJsonSupport.WorkbenchGroupNameFormat
        message.contents.parseJson.convertTo[WorkbenchGroupName]
    }).get
  }

  override val supervisorStrategy =
    OneForOneStrategy() {
      case e => {
        Escalate
      }
    }
}
