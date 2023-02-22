package org.broadinstitute.dsde.workbench.sam.google

import akka.http.scaladsl.model.StatusCodes
import cats.effect.unsafe.implicits.global
import com.google.cloud.pubsub.v1.{AckReplyConsumer, MessageReceiver}
import com.google.common.annotations.VisibleForTesting
import com.google.pubsub.v1.PubsubMessage
import com.typesafe.scalalogging.LazyLogging
import io.opencensus.trace.AttributeValue
import net.logstash.logback.argument.StructuredArguments
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.FullyQualifiedPolicyId
import org.broadinstitute.dsde.workbench.sam.util.OpenCensusIOUtils._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import spray.json._

import scala.util.Try

/** Created by dvoet on 12/6/16.
  */
class GoogleGroupSyncMessageReceiver(groupSynchronizer: GoogleGroupSynchronizer) extends MessageReceiver with LazyLogging {
  override def receiveMessage(message: PubsubMessage, consumer: AckReplyConsumer): Unit =
    traceIO("GoogleGroupSyncMessageReceiver-PubSubMessage", SamRequestContext()) { samRequestContext =>
      val groupId: WorkbenchGroupIdentity = parseMessage(message)
      logger.info(s"received sync message: $groupId")
      samRequestContext.parentSpan.foreach(
        _.putAttribute("groupId", AttributeValue.stringAttributeValue(groupId.toString))
      )
      groupSynchronizer
        .synchronizeGroupMembers(
          groupId,
          Set.empty,
          samRequestContext
        )
        .redeem(syncFailure(_, consumer), syncComplete(_, consumer))
    }.unsafeRunSync()

  /** this handles any general errors before or after adding/removing google group members
    * @param t
    * @param consumer
    */
  private def syncFailure(t: Throwable, consumer: AckReplyConsumer): Unit =
    t match {
      case groupNotFound: WorkbenchExceptionWithErrorReport if groupNotFound.errorReport.statusCode.contains(StatusCodes.NotFound) =>
        // this can happen if a group is created then removed before the sync message is handled
        // acknowledge it so we don't have to handle it again
        logger.info(s"group to synchronize not found: ${groupNotFound.errorReport}")
        consumer.ack()

      case regrets: Throwable =>
        logger.error("failure synchronizing google group", regrets)
        consumer.nack() // redeliver message to hopefully rectify the failure
    }

  /** Called when the general proccess completed without error but there still may be errors in the individual group adds or removes
    * @param report
    * @param consumer
    */
  private def syncComplete(report: Map[WorkbenchEmail, Seq[SyncReportItem]], consumer: AckReplyConsumer): Unit = {
    val errorReports = report.values.flatten.collect {
      case SyncReportItem(_, _, errorReports) if errorReports.nonEmpty => errorReports
    }.flatten

    import DefaultJsonProtocol._
    import WorkbenchIdentityJsonSupport._
    import org.broadinstitute.dsde.workbench.sam.google.SamGoogleModelJsonSupport._

    if (errorReports.isEmpty) {
      logger.info(s"synchronized google group", StructuredArguments.raw("syncDetail", report.toJson.compactPrint))
      consumer.ack()
    } else {
      logger.error(s"synchronized google group with failures", StructuredArguments.raw("syncDetail", report.toJson.compactPrint))
      consumer.nack() // redeliver message to hopefully rectify the failures
    }
  }

  @VisibleForTesting
  private[google] def parseMessage(message: PubsubMessage): WorkbenchGroupIdentity = {
    val messageJson = message.getData.toStringUtf8.parseJson
    (Try {
      import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport.FullyQualifiedPolicyIdFormat
      messageJson.convertTo[FullyQualifiedPolicyId]
    } recover { case _: DeserializationException =>
      import WorkbenchIdentityJsonSupport.WorkbenchGroupNameFormat
      messageJson.convertTo[WorkbenchGroupName]
    }).get
  }
}
