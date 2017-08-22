package org.broadinstitute.dsde.workbench.sam.config

import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets

/**
  * Created by mbemis on 8/17/17.
  */
case class GoogleDirectoryConfig(clientSecrets: GoogleClientSecrets, pemFile: String, appsDomain: String, appName: String, serviceProject: String)
