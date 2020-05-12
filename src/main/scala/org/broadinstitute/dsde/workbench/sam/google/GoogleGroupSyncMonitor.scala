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
import org.broadinstitute.dsde.workbench.sam.model.FullyQualifiedPolicyId
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.util.FutureSupport
import spray.json._
import io.opencensus.scala.Tracing

import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps
import scala.util.Try

/**
  * Created by dvoet on 12/6/16.
  */
object GoogleGroupSyncMonitorSupervisor {
  sealed trait GoogleGroupSyncMonitorSupervisorMessage
  case object Init extends GoogleGroupSyncMonitorSupervisorMessage
  case object Start extends GoogleGroupSyncMonitorSupervisorMessage

  def props(
      pollInterval: FiniteDuration,
      pollIntervalJitter: FiniteDuration,
      pubSubDao: GooglePubSubDAO,
      pubSubTopicName: String,
      pubSubSubscriptionName: String,
      workerCount: Int,
      groupSynchronizer: GoogleGroupSynchronizer): Props =
    Props(
      new GoogleGroupSyncMonitorSupervisor(pollInterval, pollIntervalJitter, pubSubDao, pubSubTopicName, pubSubSubscriptionName, workerCount, groupSynchronizer))
}

class GoogleGroupSyncMonitorSupervisor(
    val pollInterval: FiniteDuration,
    pollIntervalJitter: FiniteDuration,
    pubSubDao: GooglePubSubDAO,
    pubSubTopicName: String,
    pubSubSubscriptionName: String,
    workerCount: Int,
    groupSynchronizer: GoogleGroupSynchronizer)
    extends Actor
    with LazyLogging {
  import GoogleGroupSyncMonitorSupervisor._
  import context._

  self ! Init

  override def receive = {
    case Init => init pipeTo self
    case Start => for (i <- 1 to workerCount) startOne()
    case Status.Failure(t) => logger.error("error initializing google group sync monitor", t)
  }

  def init =
    for {
      _ <- pubSubDao.createTopic(pubSubTopicName)
      _ <- pubSubDao.createSubscription(pubSubTopicName, pubSubSubscriptionName)
    } yield Start

  def startOne(): Unit = {
    logger.info("starting GoogleGroupSyncMonitorActor")
    actorOf(GoogleGroupSyncMonitor.props(pollInterval, pollIntervalJitter, pubSubDao, pubSubSubscriptionName, groupSynchronizer))
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

  sealed abstract class SynchronizGroupMembersResult(ackId: String)
  final case class ReportMessage(value: Map[WorkbenchEmail, Seq[SyncReportItem]], ackId: String) extends SynchronizGroupMembersResult(ackId = ackId)
  final case class FailToSynchronize(t: Throwable, ackId: String) extends SynchronizGroupMembersResult(ackId = ackId)

  def props(
      pollInterval: FiniteDuration,
      pollIntervalJitter: FiniteDuration,
      pubSubDao: GooglePubSubDAO,
      pubSubSubscriptionName: String,
      groupSynchronizer: GoogleGroupSynchronizer): Props =
    Props(new GoogleGroupSyncMonitorActor(pollInterval, pollIntervalJitter, pubSubDao, pubSubSubscriptionName, groupSynchronizer))
}

class GoogleGroupSyncMonitorActor(
    val pollInterval: FiniteDuration,
    pollIntervalJitter: FiniteDuration,
    pubSubDao: GooglePubSubDAO,
    pubSubSubscriptionName: String,
    groupSynchronizer: GoogleGroupSynchronizer)
    extends Actor
    with LazyLogging
    with FutureSupport {
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
      import Tracing._
      trace("GoogleGroupSyncMonitor-PubSubMessage") { span =>
        logger.debug(s"received sync message: $message")
        val groupId: WorkbenchGroupIdentity = parseMessage(message)

        groupSynchronizer
          .synchronizeGroupMembers(groupId, samRequestContext = SamRequestContext(Option(span))) // Since this is an internal pub/sub call, we have to start a new SamRequestContext.
          .toTry
          .map(sr => sr.fold(t => FailToSynchronize(t, message.ackId), x => ReportMessage(x, message.ackId))) pipeTo self
      }

    case None =>
      // there was no message to wait and try again
      val nextTime = org.broadinstitute.dsde.workbench.util.addJitter(pollInterval, pollIntervalJitter)
      system.scheduler.scheduleOnce(nextTime.asInstanceOf[FiniteDuration], self, StartMonitorPass)

    case ReportMessage(report, ackId) =>
      val errorReports = report.values.flatten.collect {
        case SyncReportItem(_, _, errorReports) if errorReports.nonEmpty => errorReports
      }.flatten

      if (errorReports.isEmpty) {
        // sync done, log it and try again immediately
        acknowledgeMessage(ackId).map(_ => StartMonitorPass) pipeTo self

        import DefaultJsonProtocol._
        import WorkbenchIdentityJsonSupport._
        import org.broadinstitute.dsde.workbench.sam.google.GoogleModelJsonSupport._
        logger.info(s"synchronized google group ${report.toJson.compactPrint}")
      } else {
        throw new WorkbenchExceptionWithErrorReport(ErrorReport("error(s) syncing google group", errorReports.toSeq))
      }

    case FailToSynchronize(t, ackId) =>
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

  private def acknowledgeMessage(ackId: String): Future[Unit] =
    pubSubDao.acknowledgeMessagesById(pubSubSubscriptionName, Seq(ackId))

  private def parseMessage(message: PubSubMessage): WorkbenchGroupIdentity =
    (Try {
      import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport.PolicyIdentityFormat
      message.contents.parseJson.convertTo[FullyQualifiedPolicyId]
    } recover {
      case _: DeserializationException =>
        import WorkbenchIdentityJsonSupport.WorkbenchGroupNameFormat
        message.contents.parseJson.convertTo[WorkbenchGroupName]
    }).get

  override def postStop(): Unit = logger.info(s"GoogleGroupSyncMonitorActor $self terminated")

  override val supervisorStrategy =
    OneForOneStrategy() {
      case e => {
        Escalate
      }
    }
}
