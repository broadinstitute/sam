package org.broadinstitute.dsde.workbench.sam.model

import org.broadinstitute.dsde.workbench.model.WorkbenchGroupName

case class FilterResourcesResult(
    resourceId: ResourceId,
    resourceTypeName: ResourceTypeName,
    parentResourceId: Option[FullyQualifiedResourceId],
    policy: Option[AccessPolicyName],
    role: Option[ResourceRoleName],
    action: Option[ResourceAction],
    isPublic: Boolean,
    authDomain: Option[WorkbenchGroupName],
    inAuthDomain: Boolean,
    inherited: Boolean
)
