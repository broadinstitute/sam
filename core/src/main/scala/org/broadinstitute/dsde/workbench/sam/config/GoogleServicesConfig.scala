package org.broadinstitute.dsde.workbench.sam.config

import scala.concurrent.duration.FiniteDuration

/**
  * Created by mbemis on 8/17/17.
  */
case class GoogleServicesConfig(appName: String,
                                appsDomain: String,
                                pemFile: String,
                                serviceAccountClientId: String,
                                serviceAccountClientEmail: String,
                                serviceAccountClientProject: String,
                                subEmail: String,
                                groupSyncPubSubProject: String,
                                groupSyncPollInterval: FiniteDuration,
                                groupSyncPollJitter: FiniteDuration,
                                groupSyncTopic: String,
                                groupSyncSubscription: String,
                                groupSyncWorkerCount: Int
                               )
