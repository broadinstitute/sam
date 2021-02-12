package org.broadinstitute.dsde.workbench.sam.config

import scala.concurrent.duration.FiniteDuration

case class GooglePubSubMonitorConfig (
  pollInterval: FiniteDuration,
  pollJitter: FiniteDuration,
  topic: String,
  subscription: String,
  workerCount: Int
)
