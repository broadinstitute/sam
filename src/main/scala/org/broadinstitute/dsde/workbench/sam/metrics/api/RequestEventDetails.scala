package org.broadinstitute.dsde.workbench.sam.metrics

import akka.http.scaladsl.model.HttpRequest
import org.broadinstitute.dsde.workbench.sam.api.OIDCHeaders

import java.util
import scala.jdk.CollectionConverters._

case class RequestEventDetails(httpRequest: HttpRequest, oidcHeaders: Option[OIDCHeaders]) extends MetricsLoggable {
  override def toLoggableMap: util.Map[String, Any] = {
    val baseMap = Map[String, Any](
      "path" -> httpRequest.uri.path.toString(),
      "method" -> httpRequest.method.value
    )
    oidcHeaders.map(headers => (baseMap + ("oidcHeaders" -> headers.toLoggableMap)).asJava).getOrElse(baseMap.asJava)
  }
}
