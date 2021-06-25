package org.broadinstitute.dsde.workbench.sam.config

import scala.concurrent.duration.FiniteDuration

case class GooglePubSubConfig(project: String,
                              pollInterval: FiniteDuration,
                              pollJitter: FiniteDuration,
                              topic: String,
                              subscription: String,
                              workerCount: Int)
