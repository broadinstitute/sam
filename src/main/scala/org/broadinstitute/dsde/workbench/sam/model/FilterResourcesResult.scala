package org.broadinstitute.dsde.workbench.sam.model

case class FilterResourcesResult(
    resourceId: ResourceId,
    resourceTypeName: ResourceTypeName,
    policy: Option[AccessPolicyName],
    role: Option[ResourceRoleName],
    action: Option[ResourceAction],
    isPublic: Boolean
)
