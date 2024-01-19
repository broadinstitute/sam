package org.broadinstitute.dsde.workbench.sam.model

import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccount}

// This should live in wb-libs

case class ActionServiceAccountId(resourceId: ResourceId, action: ResourceAction, project: GoogleProject)
case class ActionServiceAccount(id: ActionServiceAccountId, serviceAccount: ServiceAccount)
