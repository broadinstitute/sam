package org.broadinstitute.dsde.workbench.sam.config

import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.model.google.GoogleProject

import scala.concurrent.duration.FiniteDuration

/**
  * Created by mbemis on 8/17/17.
  */
case class GoogleServicesConfig(appName: String,
                                appsDomain: String,
                                pemFile: String,
                                serviceAccountClientId: String,
                                serviceAccountClientEmail: WorkbenchEmail,
                                serviceAccountClientProject: GoogleProject,
                                subEmail: WorkbenchEmail,
                                projectServiceAccount: WorkbenchEmail,
                                groupSyncPubSubProject: String,
                                groupSyncPollInterval: FiniteDuration,
                                groupSyncPollJitter: FiniteDuration,
                                groupSyncTopic: String,
                                groupSyncSubscription: String,
                                groupSyncWorkerCount: Int,
                                googleKeyCacheConfig: GoogleKeyCacheConfig,
                                resourceNamePrefix: Option[String]
                               )
