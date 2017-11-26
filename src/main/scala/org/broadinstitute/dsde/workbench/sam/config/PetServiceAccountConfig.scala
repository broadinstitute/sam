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
  */
case class PetServiceAccountConfig(googleProject: GoogleProject, serviceAccountUsers: Set[WorkbenchEmail])
