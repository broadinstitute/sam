package org.broadinstitute.dsde.workbench.sam.google

import cats.effect.unsafe.implicits.global
import com.google.cloud.pubsub.v1.{AckReplyConsumer, MessageReceiver}
import com.google.pubsub.v1.PubsubMessage
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.UserStatus
import org.broadinstitute.dsde.workbench.sam.service.UserService
import org.broadinstitute.dsde.workbench.sam.util.{OpenCensusIOUtils, SamRequestContext}

/** Created by srubenst on 02/04/21.
  */
class DisableUserMessageReceiver(userService: UserService) extends MessageReceiver with LazyLogging {
  override def receiveMessage(message: PubsubMessage, consumer: AckReplyConsumer): Unit =
    OpenCensusIOUtils
      .traceIO("DisableUsersMonitor-PubSubMessage", SamRequestContext()) { samRequestContext =>
        val userId = WorkbenchUserId(message.getData.toStringUtf8)
        logger.info(s"received disable user message: $userId")
        userService
          .disableUser(userId, samRequestContext)
          .redeem(handleFailure(_, consumer), handleDisableUserResponse(userId, _, consumer))
      }
      .unsafeRunSync()

  private def handleDisableUserResponse(userId: WorkbenchUserId, maybeStatus: Option[UserStatus], consumer: AckReplyConsumer) = {
    maybeStatus match {
      case Some(_) =>
        // If we have gotten to this point, the disableUser method has run, so we can assume the user is disabled
        logger.info(s"disabled user: $userId ")
      case None =>
        logger.info(s"user to disable not found: $userId")
    }
    consumer.ack()
  }

  private def handleFailure(t: Throwable, consumer: AckReplyConsumer) = {
    logger.error("failed to disable user", t)
    consumer.nack()
  }
}
