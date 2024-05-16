package org.broadinstitute.dsde.workbench.sam.metrics

import akka.http.scaladsl.model.HttpResponse

import java.util
import scala.jdk.CollectionConverters._

case class ResponseEventDetails(httpResponse: HttpResponse) extends MetricsLoggable {
  override def toLoggableMap: util.Map[String, Any] = Map[String, Any](
    "status" -> httpResponse.status.intValue
  ).asJava
}
