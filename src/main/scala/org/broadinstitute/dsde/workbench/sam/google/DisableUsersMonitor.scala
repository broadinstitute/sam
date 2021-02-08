package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.SupervisorStrategy.{Escalate, Stop}
import akka.actor._
import akka.http.scaladsl.model.StatusCodes
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.GooglePubSubDAO
import org.broadinstitute.dsde.workbench.google.GooglePubSubDAO.PubSubMessage
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.util.FutureSupport
import spray.json._
import io.opencensus.scala.Tracing
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport.WorkbenchUserIdFormat
import org.broadinstitute.dsde.workbench.sam.service.UserService

import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}

/**
  * Created by srubenst on 02/04/21.
  */
object DisableUsersMonitorSupervisor {
  sealed trait DisableUsersMonitorSupervisorMessage
  case object Init extends DisableUsersMonitorSupervisorMessage
  case object Start extends DisableUsersMonitorSupervisorMessage

  def props(
             pollInterval: FiniteDuration,
             pollIntervalJitter: FiniteDuration,
             pubSubDao: GooglePubSubDAO,
             pubSubTopicName: String,
             pubSubSubscriptionName: String,
             workerCount: Int,
             userService: UserService
           ): Props =
    Props(new DisableUsersMonitorSupervisor(pollInterval, pollIntervalJitter, pubSubDao, pubSubTopicName, pubSubSubscriptionName, workerCount, userService))
}

class DisableUsersMonitorSupervisor(
  val pollInterval: FiniteDuration,
  pollIntervalJitter: FiniteDuration,
  pubSubDao: GooglePubSubDAO,
  pubSubTopicName: String,
  pubSubSubscriptionName: String,
  workerCount: Int,
  userService: UserService
) extends Actor with LazyLogging {
  import DisableUsersMonitorSupervisor._
  import context._

  self ! Init

  override def receive = {
    case Init => init pipeTo self
    case Start => for (i <- 1 to workerCount) startOne()
    case Status.Failure(t) => logger.error("error initializing disable users monitor", t)
  }

  def init =
    for {
      _ <- pubSubDao.createSubscription(pubSubTopicName, pubSubSubscriptionName)
    } yield Start

  def startOne(): Unit = {
    logger.info("starting DisableUsersMonitorActor")
    actorOf(DisableUsersMonitor.props(pollInterval, pollIntervalJitter, pubSubDao, pubSubSubscriptionName, userService))
  }

  override val supervisorStrategy =
    OneForOneStrategy() {
      case e =>
        logger.error("unexpected error in disable users monitor", e)
        // start one to replace the error, stop the errored child so that we also drop its mailbox (i.e. restart not good enough)
        startOne()
        Stop
    }

}

object DisableUsersMonitor {
  case object StartMonitorPass

  sealed abstract class DisableUserResult(ackId: String)
  final case class ReportMessage(value: Map[WorkbenchEmail, Seq[SyncReportItem]], ackId: String) extends DisableUserResult(ackId = ackId)
  final case class FailToSynchronize(t: Throwable, ackId: String) extends DisableUserResult(ackId = ackId)

  def props(
             pollInterval: FiniteDuration,
             pollIntervalJitter: FiniteDuration,
             pubSubDao: GooglePubSubDAO,
             pubSubSubscriptionName: String,
             userService: UserService
           ): Props =
    Props(new DisableUsersMonitorActor(pollInterval, pollIntervalJitter, pubSubDao, pubSubSubscriptionName, userService))
}

class DisableUsersMonitorActor(
  val pollInterval: FiniteDuration,
  pollIntervalJitter: FiniteDuration,
  pubSubDao: GooglePubSubDAO,
  pubSubSubscriptionName: String,
  userService: UserService
) extends Actor with LazyLogging with FutureSupport {
  import GoogleGroupSyncMonitor._
  import context._

  self ! StartMonitorPass

  // fail safe in case this actor is idle too long but not too fast (1 second lower limit)
  setReceiveTimeout(max((pollInterval + pollIntervalJitter) * 10, 1 second))

  private def max(durations: FiniteDuration*): FiniteDuration = {
    implicit val finiteDurationIsOrdered = scala.concurrent.duration.FiniteDuration.FiniteDurationIsOrdered
    durations.max
  }

  override def receive = {
    case StartMonitorPass =>
      // start the process by pulling a message and sending it back to self
      pubSubDao.pullMessages(pubSubSubscriptionName, 1).map(_.headOption) pipeTo self

    case Some(message: PubSubMessage) =>
      logger.debug(s"received disable user message: $message")
      import Tracing._
      trace("DisableUsersMonitor-PubSubMessage") { span =>
        userService
          .disableUser(message.contents.parseJson.convertTo[WorkbenchUserId], samRequestContext = SamRequestContext(Option(span)))
      }

    case None =>
      // there was no message to wait and try again
      val nextTime = org.broadinstitute.dsde.workbench.util.addJitter(pollInterval, pollIntervalJitter)
      system.scheduler.scheduleOnce(nextTime.asInstanceOf[FiniteDuration], self, StartMonitorPass)

    case ReportMessage(report, ackId) =>
      import Tracing._
      trace("DisableUsersMonitor-ReportMessage") { _ =>
        val errorReports = report.values.flatten.collect {
          case SyncReportItem(_, _, errorReports) if errorReports.nonEmpty => errorReports
        }.flatten

        if (errorReports.isEmpty) {
          // sync done, log it and try again immediately
          acknowledgeMessage(ackId).map(_ => StartMonitorPass) pipeTo self

          import DefaultJsonProtocol._
          import WorkbenchIdentityJsonSupport._
          import org.broadinstitute.dsde.workbench.sam.google.SamGoogleModelJsonSupport._
          logger.info(s"disabled user ${report.toJson.compactPrint}")
          Future.successful(None)
        } else {
          throw new WorkbenchExceptionWithErrorReport(ErrorReport("error(s) disabling user", errorReports.toSeq))
        }
      }

    case FailToSynchronize(t, ackId) =>
      t match {
        case userNotFound: WorkbenchExceptionWithErrorReport if userNotFound.errorReport.statusCode.contains(StatusCodes.NotFound) =>
          // this can happen if a user is deleted before the sync message is handled
          // acknowledge it so we don't have to handle it again
          acknowledgeMessage(ackId).map(_ => StartMonitorPass) pipeTo self
          logger.info(s"user to synchronize not found: ${userNotFound.errorReport}")

        case regrets: Throwable => throw regrets
      }

    case Status.Failure(t) => throw t
    // Do we want this?
    case ReceiveTimeout =>
      throw new WorkbenchException("DisableUsersMonitorActor has received no messages for too long")

    case x => logger.info(s"unhandled $x")
  }

  private def acknowledgeMessage(ackId: String): Future[Unit] =
    pubSubDao.acknowledgeMessagesById(pubSubSubscriptionName, Seq(ackId))

  override def postStop(): Unit = logger.info(s"GoogleGroupSyncMonitorActor $self terminated")

  override val supervisorStrategy =
    OneForOneStrategy() {
      case e => {
        Escalate
      }
    }
}
