package org.broadinstitute.dsde.workbench.sam.metrics

import akka.http.scaladsl.server.Rejection
import org.broadinstitute.dsde.workbench.model.WorkbenchUserId

import java.util
import scala.jdk.CollectionConverters._

case class RegisteredUserApiEvent(
    samUserId: WorkbenchUserId,
    allowed: Boolean,
    override val event: String,
    override val request: RequestEventDetails,
    override val response: Option[ResponseEventDetails] = None,
    override val rejections: Seq[Rejection] = Seq()
) extends ApiEvent {

  override def toLoggableMap: util.Map[String, Any] =
    (super.toScalaMap + ("samUserId" -> samUserId.value, "allowed" -> allowed)).asJava
}
