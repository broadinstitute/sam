package org.broadinstitute.dsde.workbench.sam.config

import org.broadinstitute.dsde.workbench.model.google._
import org.broadinstitute.dsde.workbench.model.WorkbenchEmail

/**
  * Pet Service Account configuration.
  * @param googleProject The project in which to create pet service accounts.
  * @param serviceAccountUsers Set of emails for which to grant the Service Account User role
  *                            on pet service accounts. Generally used for cases where other
  *                            application service accounts need to impersonate as the pet service
  *                            account.
  * @param keyBucketName The name of the bucket to store all pet service account keys in
  * @param activeKeyMaxAge The number of days to keep a key active
  * @param retiredKeyMaxAge The number of days to keep a key before deleting it
  */
case class PetServiceAccountConfig(googleProject: GoogleProject, serviceAccountUsers: Set[WorkbenchEmail], keyBucketName: String, activeKeyMaxAge: Int, retiredKeyMaxAge: Int)
