package org.broadinstitute.dsde.workbench.sam.google

import cats.data.OptionT
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.cloud.pubsub.v1.{AckReplyConsumer, MessageReceiver}
import com.google.pubsub.v1.PubsubMessage
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.google.GoogleIamDAO
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountKeyId}
import org.broadinstitute.dsde.workbench.sam.util.{OpenCensusIOUtils, SamRequestContext}
import spray.json._

/** Created by mbemis on 1/19/18.
  */
class GoogleKeyCacheMessageReceiver(googleIamDAO: GoogleIamDAO) extends MessageReceiver with LazyLogging {

  override def receiveMessage(message: PubsubMessage, consumer: AckReplyConsumer): Unit =
    OpenCensusIOUtils
      .traceIO("GoogleKeyCacheMonitor--PubSubMessage", SamRequestContext()) { samRequestContext =>
        logger.info(s"received key deletion message: ${message.getData.toStringUtf8}")
        val (project, serviceAccountEmail, keyId) = parseMessage(message)
        removeServiceAccountKey(project, serviceAccountEmail, keyId)
          .redeem(handleError(consumer, _), handleSuccess(consumer, _))
      }
      .unsafeRunSync()

  def handleSuccess(consumer: AckReplyConsumer, maybeKeyId: Option[ServiceAccountKeyId]) = {
    maybeKeyId.foreach(keyId => logger.info(s"removed service account key $keyId"))
    consumer.ack()
  }

  private def handleError(consumer: AckReplyConsumer, t: Throwable) = {
    logger.error("error removing service account key", t)
    consumer.nack()
  }

  private def removeServiceAccountKey(project: GoogleProject, serviceAccountEmail: WorkbenchEmail, keyId: ServiceAccountKeyId) = {
    for {
      _ <- OptionT(IO.fromFuture(IO(googleIamDAO.findServiceAccount(project, serviceAccountEmail))).recover {
        case t: GoogleJsonResponseException if t.getStatusCode == 403 =>
          logger.warn(s"could not remove service account key due to 403 error, project $project, sa email $serviceAccountEmail, sa key id $keyId", t)
          None
      })
      keys <- OptionT.liftF(IO.fromFuture(IO(googleIamDAO.listServiceAccountKeys(project, serviceAccountEmail))))
      _ <- OptionT.whenF(keys.map(_.id).contains(keyId)) {
        IO.fromFuture(IO(googleIamDAO.removeServiceAccountKey(project, serviceAccountEmail, keyId)))
      }
    } yield keyId
  }.value

  private[google] def parseMessage(message: PubsubMessage): (GoogleProject, WorkbenchEmail, ServiceAccountKeyId) = {
    val objectIdPattern = """"([^/]+)/([^/]+)/([^/]+)"""".r

    message.getData.toStringUtf8.parseJson.asJsObject.getFields("name").head.compactPrint match {
      case objectIdPattern(project, serviceAccountEmail, keyId) => (GoogleProject(project), WorkbenchEmail(serviceAccountEmail), ServiceAccountKeyId(keyId))
      case m => throw new Exception(s"unable to parse message $m")
    }
  }
}
