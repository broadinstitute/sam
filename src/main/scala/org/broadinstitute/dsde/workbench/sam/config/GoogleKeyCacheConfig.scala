package org.broadinstitute.dsde.workbench.sam.config

import scala.concurrent.duration.FiniteDuration

/**
  * Created by mbemis on 1/22/18.
  */
case class GoogleKeyCacheConfig(bucketName: String,
                                activeKeyMaxAge: Int,
                                retiredKeyMaxAge: Int,
                                monitorPubSubProject: String,
                                monitorPollInterval: FiniteDuration,
                                monitorPollJitter: FiniteDuration,
                                monitorTopic: String,
                                monitorSubscription: String,
                                monitorWorkerCount: Int
                               )