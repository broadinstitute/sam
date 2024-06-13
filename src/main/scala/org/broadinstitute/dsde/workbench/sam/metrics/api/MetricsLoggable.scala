package org.broadinstitute.dsde.workbench.sam.metrics

trait MetricsLoggable {

  def toLoggableMap: java.util.Map[String, Any]

}
