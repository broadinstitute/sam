package org.broadinstitute.dsde.workbench.test

import org.broadinstitute.dsde.workbench.config.CommonConfig

object SamConfig extends CommonConfig {
  // from common: qaEmail, pathToQAPem
  object GCS extends CommonGCS {
    val appsDomain = gcsConfig.getString("appsDomain")
    val pathToSamTestFirestoreAccountPath = gcsConfig.getString("firestoreAccountPath")
  }
}
