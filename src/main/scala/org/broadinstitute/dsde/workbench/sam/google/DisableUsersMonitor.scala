package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.SupervisorStrategy.{Escalate, Stop}
import akka.actor._
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.GooglePubSubDAO
import org.broadinstitute.dsde.workbench.google.GooglePubSubDAO.PubSubMessage
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.util.FutureSupport
import io.opencensus.scala.Tracing
import org.broadinstitute.dsde.workbench.sam.model.UserStatus
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
             disableUsersPubSubDao: GooglePubSubDAO,
             pubSubTopicName: String,
             pubSubSubscriptionName: String,
             workerCount: Int,
             userService: UserService
           ): Props =
    Props(new DisableUsersMonitorSupervisor(pollInterval, pollIntervalJitter, disableUsersPubSubDao, pubSubTopicName, pubSubSubscriptionName, workerCount, userService))
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

  override def receive: Receive = {
    case Init => init pipeTo self
    case Start => for (_ <- 1 to workerCount) startOne()
    case Status.Failure(t) => logger.error("error initializing disable users monitor", t)
  }

  def init: Future[DisableUsersMonitorSupervisor.Start.type] =
    for {
      _ <- pubSubDao.createSubscription(pubSubTopicName, pubSubSubscriptionName)
    } yield Start

  def startOne(): Unit = {
    logger.info("starting DisableUsersMonitorActor")
    actorOf(DisableUsersMonitor.props(pollInterval, pollIntervalJitter, pubSubDao, pubSubSubscriptionName, userService))
  }

  override val supervisorStrategy: OneForOneStrategy =
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

  final case class DisableUserResponse(userId: WorkbenchUserId, value: Option[UserStatus])
  final case class ReportMessage(value: DisableUserResponse, ackId: String)
  final case class FailToDisable(t: Throwable, ackId: String)

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
  import DisableUsersMonitor._
  import context._

  self ! StartMonitorPass

  // fail safe in case this actor is idle too long but not too fast (1 second lower limit)
  setReceiveTimeout(max((pollInterval + pollIntervalJitter) * 10, 1 second))

  private def max(durations: FiniteDuration*): FiniteDuration = {
    implicit val finiteDurationIsOrdered: FiniteDuration.FiniteDurationIsOrdered.type = scala.concurrent.duration.FiniteDuration.FiniteDurationIsOrdered
    durations.max
  }

  override def receive: Receive = {
    case StartMonitorPass =>
      // start the process by pulling a message and sending it back to self
      pubSubDao.pullMessages(pubSubSubscriptionName, 1).map(_.headOption) pipeTo self

    case Some(message: PubSubMessage) =>
      attemptToDisableUser(message) pipeTo self

    case None =>
      // there was no message to wait and try again
      val nextTime = org.broadinstitute.dsde.workbench.util.addJitter(pollInterval, pollIntervalJitter)
      system.scheduler.scheduleOnce(nextTime.asInstanceOf[FiniteDuration], self, StartMonitorPass)

    case ReportMessage(disableUserResponse, ackId) =>
      handleDisableUserResponse(disableUserResponse, ackId) pipeTo self

    case FailToDisable(t, ackId) => throw t

    case Status.Failure(t) => throw t

    case ReceiveTimeout =>
      throw new WorkbenchException("DisableUsersMonitorActor has received no messages for too long")

    case x => logger.info(s"Received unhandleable message in DisableUsersMonitor", x)
  }

  private def handleDisableUserResponse(disableUserResponse: DisableUserResponse, ackId: String) = {
    Tracing.trace("DisableUsersMonitor-ReportMessage") { _ =>
      disableUserResponse.value match {
        case Some(_) =>
          // If we have gotten to this point, the disableUser method has run, so we can assume the user is disabled
          logger.info(s"disabled user: ${disableUserResponse.userId} ")
        case None =>
          logger.info(s"user to disable not found: ${disableUserResponse.userId}")
      }
      acknowledgeMessage(ackId).map(_ => StartMonitorPass)
    }
  }

  private def attemptToDisableUser(message: PubSubMessage) = {
    logger.debug(s"received disable user message: $message")
    val userId = WorkbenchUserId(message.contents)
    Tracing.trace("DisableUsersMonitor-PubSubMessage") { span =>
      userService
        .disableUser(userId, samRequestContext = SamRequestContext(Option(span)))
        .toTry
        .map(dr => dr.fold(t => FailToDisable(t, message.ackId), maybeUserStatus => ReportMessage(DisableUserResponse(userId, maybeUserStatus), message.ackId)))
    }
  }

  private def acknowledgeMessage(ackId: String): Future[Unit] =
    pubSubDao.acknowledgeMessagesById(pubSubSubscriptionName, Seq(ackId))

  override def postStop(): Unit = logger.info(s"DisableUsersMonitorActor $self terminated")

  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy() {
      case _ =>
        Escalate
    }
}
