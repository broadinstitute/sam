package org.broadinstitute.dsde.workbench.sam.config

case class GooglePubSubConfig(
  pubSubProject: String,
  groupSyncMonitorConfig: GooglePubSubMonitorConfig,
  disableUsersMonitorConfig: GooglePubSubMonitorConfig,
  googleKeyCacheMonitorConfig: GooglePubSubMonitorConfig
)
