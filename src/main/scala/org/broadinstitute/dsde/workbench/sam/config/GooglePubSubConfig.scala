package org.broadinstitute.dsde.workbench.sam.config

case class GooglePubSubConfig(project: String, topic: String, subscription: String, workerCount: Int)
