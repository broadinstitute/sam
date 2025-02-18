package org.broadinstitute.dsde.workbench.sam.config

import java.time.Duration

case class GooglePubSubConfig(project: String, topic: String, subscription: String, workerCount: Int, maxAckExtensionPeriod: Duration)
