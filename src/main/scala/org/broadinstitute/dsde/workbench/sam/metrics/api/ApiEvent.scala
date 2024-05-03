package org.broadinstitute.dsde.workbench.sam.metrics

import akka.http.scaladsl.server.Rejection
import net.logstash.logback.argument.{StructuredArgument, StructuredArguments}

import java.util
import scala.jdk.CollectionConverters._

trait ApiEvent extends MetricsLoggable {

  def event: String
  def request: RequestEventDetails
  def response: Option[ResponseEventDetails] = None
  def rejections: Seq[Rejection] = Seq()

  override def toLoggableMap: util.Map[String, Any] =
    toScalaMap.asJava

  protected def toScalaMap: Map[String, Any] = {
    val baseMap = Map[String, Any](
      "warehouse" -> true,
      "event" -> event
    ) + ("request" -> request.toLoggableMap)

    val responseMap = response.map(headers => baseMap + ("response" -> headers.toLoggableMap)).getOrElse(baseMap)

    if (rejections.isEmpty) {
      responseMap
    } else {
      responseMap + ("rejections" -> rejections.map(_.getClass.getSimpleName).asJava)
    }
  }

  def toStructuredArguments: StructuredArgument =
    StructuredArguments.keyValue("eventProperties", toLoggableMap)
}
