package org.broadinstitute.dsde.workbench.sam.config

/**
  * Created by dvoet on 5/26/17.
  */
case class DirectoryConfig(directoryUrl: String, user: String, password: String, baseDn: String, enabledUsersGroupDn: String, connectionPoolSize: Int = 20)
