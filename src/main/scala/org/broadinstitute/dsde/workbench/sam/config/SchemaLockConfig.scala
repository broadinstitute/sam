package org.broadinstitute.dsde.workbench.sam.config

/**
  * Created by mbemis on 2/27/18.
  */

/**
  * Schema lock configuration.
  * @param lockSchemaOnBoot Set to false to disable schema locking altogether
  * @param recheckTimeInterval The number of seconds to wait before retrying the lock (should be less than or equal to maxTimeToWait)
  * @param maxTimeToWait The number of seconds to wait before giving up
  * @param instanceId The id of the instance that is dealing with the lock
  * @param schemaVersion The version of the schema, to be incremented each time the schema changes
  */
case class SchemaLockConfig(lockSchemaOnBoot: Boolean,
                            recheckTimeInterval: Int,
                            maxTimeToWait: Int,
                            instanceId: String,
                            schemaVersion: Int)
