package org.broadinstitute.dsde.workbench.sam.config

import org.broadinstitute.dsde.workbench.model.google.GoogleProject

import scala.concurrent.duration.FiniteDuration

/**
  * Created by mbemis on 8/17/17.
  */
case class GoogleServicesConfig(appName: String,
                                appsDomain: String,
                                pemFile: String,
                                serviceAccountClientId: String,
                                serviceAccountClientEmail: String,
                                serviceAccountClientProject: GoogleProject,
                                subEmail: String,
                                projectServiceAccount: String,
                                groupSyncPubSubProject: String,
                                groupSyncPollInterval: FiniteDuration,
                                groupSyncPollJitter: FiniteDuration,
                                groupSyncTopic: String,
                                groupSyncSubscription: String,
                                groupSyncWorkerCount: Int,
                                googleKeyCacheConfig: GoogleKeyCacheConfig
                               )
