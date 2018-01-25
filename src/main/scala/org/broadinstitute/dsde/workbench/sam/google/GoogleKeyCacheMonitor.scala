package org.broadinstitute.dsde.workbench.sam.google

import akka.actor.SupervisorStrategy.{Escalate, Stop}
import akka.actor._
import akka.pattern._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.GooglePubSubDAO.PubSubMessage
import org.broadinstitute.dsde.workbench.google.{GoogleIamDAO, GooglePubSubDAO}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKeyId}
import org.broadinstitute.dsde.workbench.util.FutureSupport
import spray.json._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{FiniteDuration, _}
import scala.language.postfixOps

/**
  * Created by mbemis on 1/19/18.
  */
object GoogleKeyCacheMonitorSupervisor {
  sealed trait GoogleKeyCacheMonitorSupervisorMessage
  case object Init extends GoogleKeyCacheMonitorSupervisorMessage
  case object Start extends GoogleKeyCacheMonitorSupervisorMessage

  def props(pollInterval: FiniteDuration, pollIntervalJitter: FiniteDuration, pubSubDao: GooglePubSubDAO, googleIamDAO: GoogleIamDAO, pubSubTopicName: String, pubSubSubscriptionName: String, projectServiceAccount: String, workerCount: Int, googleKeyCache: GoogleKeyCache)(implicit executionContext: ExecutionContext): Props = {
    Props(new GoogleKeyCacheMonitorSupervisor(pollInterval, pollIntervalJitter, pubSubDao, googleIamDAO, pubSubTopicName, pubSubSubscriptionName, projectServiceAccount, workerCount, googleKeyCache))
  }
}

class GoogleKeyCacheMonitorSupervisor(val pollInterval: FiniteDuration, pollIntervalJitter: FiniteDuration, pubSubDao: GooglePubSubDAO, googleIamDAO: GoogleIamDAO, pubSubTopicName: String, pubSubSubscriptionName: String, projectServiceAccount: String, workerCount: Int, googleKeyCache: GoogleKeyCache)(implicit executionContext: ExecutionContext) extends Actor with LazyLogging {
  import GoogleKeyCacheMonitorSupervisor._
  import context._

  self ! Init

  override def receive = {
    case Init => init pipeTo self
    case Start => for(i <- 1 to workerCount) startOne()
    case Status.Failure(t) => logger.error("error initializing google key cache monitor", t)
  }

  def topicToFullPath(topicName: String) = s"projects/${googleKeyCache.googleServicesConfig.serviceAccountClientProject}/topics/${topicName}"

  def init = {
    for {
      _ <- pubSubDao.createTopic(pubSubTopicName)
      _ <- pubSubDao.createSubscription(pubSubTopicName, pubSubSubscriptionName)
      _ <- pubSubDao.grantTopicIamPermissions(pubSubTopicName, Map(WorkbenchEmail(projectServiceAccount) -> "roles/pubsub.publisher"))
      _ <- googleKeyCache.googleStorageDAO.setObjectChangePubSubTrigger(googleKeyCache.googleServicesConfig.googleKeyCacheConfig.bucketName, topicToFullPath(pubSubTopicName), Seq("OBJECT_DELETE"))
    } yield Start
  }

  def startOne(): Unit = {
    logger.info("starting GoogleKeyCacheMonitorActor")
    actorOf(GoogleKeyCacheMonitor.props(pollInterval, pollIntervalJitter, pubSubDao, googleIamDAO, pubSubSubscriptionName, googleKeyCache))
  }

  override val supervisorStrategy =
    OneForOneStrategy() {
      case e => {
        logger.error("unexpected error in google key cache monitor", e)
        // start one to replace the error, stop the errored child so that we also drop its mailbox (i.e. restart not good enough)
        startOne()
        Stop
      }
    }

}

object GoogleKeyCacheMonitor {
  case object StartMonitorPass

  def props(pollInterval: FiniteDuration, pollIntervalJitter: FiniteDuration, pubSubDao: GooglePubSubDAO, googleIamDAO: GoogleIamDAO, pubSubSubscriptionName: String, googleKeyCache: GoogleKeyCache)(implicit executionContext: ExecutionContext): Props = {
    Props(new GoogleKeyCacheMonitorActor(pollInterval, pollIntervalJitter, pubSubDao, googleIamDAO, pubSubSubscriptionName, googleKeyCache))
  }
}

class GoogleKeyCacheMonitorActor(val pollInterval: FiniteDuration, pollIntervalJitter: FiniteDuration, pubSubDao: GooglePubSubDAO, googleIamDAO: GoogleIamDAO, pubSubSubscriptionName: String, googleKeyCache: GoogleKeyCache)(implicit executionContext: ExecutionContext) extends Actor with LazyLogging with FutureSupport {
  import GoogleKeyCacheMonitor._
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
      logger.debug(s"received sync message: $message")
      println(message)
      val (project, serviceAccountEmail, keyId) = parseMessage(message)
      googleIamDAO.removeServiceAccountKey(project, serviceAccountEmail, keyId).map(response => (response, message.ackId)) pipeTo self

    case None =>
      // there was no message to wait and try again
      val nextTime = org.broadinstitute.dsde.workbench.util.addJitter(pollInterval, pollIntervalJitter)
      system.scheduler.scheduleOnce(nextTime.asInstanceOf[FiniteDuration], self, StartMonitorPass)

    case ((), ackId: String) =>
      acknowledgeMessage(ackId).map(_ => StartMonitorPass) pipeTo self

    case Status.Failure(t) => throw t

    case ReceiveTimeout =>
      throw new WorkbenchException("GoogleKeyCacheMonitorActor has received no messages for too long")

    case x => logger.info(s"unhandled $x")
  }

  private def acknowledgeMessage(ackId: String) = {
    pubSubDao.acknowledgeMessagesById(pubSubSubscriptionName, Seq(ackId))
  }

  private def parseMessage(message: PubSubMessage) = {
    val objectIdPattern = """"([^/]+)/([^/]+)/([^/]+)"""".r

    message.contents.parseJson.asJsObject.getFields("name").head.compactPrint match {
      case objectIdPattern(project, serviceAccountEmail, keyId) => (GoogleProject(project), WorkbenchEmail(serviceAccountEmail), ServiceAccountKeyId(keyId))
      case m => throw new Exception(s"unable to parse message $m")
    }
  }

  override val supervisorStrategy =
    OneForOneStrategy() {
      case e => {
        Escalate
      }
    }
}