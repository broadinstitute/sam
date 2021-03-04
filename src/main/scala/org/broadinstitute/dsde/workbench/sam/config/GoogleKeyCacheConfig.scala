package org.broadinstitute.dsde.workbench.sam.config

import org.broadinstitute.dsde.workbench.model.google.GcsBucketName

/**
  * Created by mbemis on 1/22/18.
  */
/**
  * Google Key Cache configuration.
  * @param bucketName The name of the bucket to store all pet service account keys in
  * @param activeKeyMaxAge The number of days to keep a key active
  * @param retiredKeyMaxAge The number of days to keep a key before deleting it
  */
case class GoogleKeyCacheConfig(
    bucketName: GcsBucketName,
    activeKeyMaxAge: Int,
    retiredKeyMaxAge: Int,
    monitorPubSubConfig: GooglePubSubConfig)
