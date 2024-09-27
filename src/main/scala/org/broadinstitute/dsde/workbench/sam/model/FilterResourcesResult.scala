package org.broadinstitute.dsde.workbench.sam.model

import org.broadinstitute.dsde.workbench.model.WorkbenchGroupName

case class FilterResourcesResult(
    resourceId: ResourceId,
    resourceTypeName: ResourceTypeName,
    policy: AccessPolicyName,
    roleOrAction: Either[ResourceRoleName, ResourceAction],
    isPublic: Boolean,
    authDomain: Option[WorkbenchGroupName],
    inAuthDomain: Boolean,
    inherited: Boolean
)
