package org.broadinstitute.dsde.workbench.sam.model

case class FilterResourcesResult(
    resourceId: ResourceId,
    resourceTypeName: ResourceTypeName,
    policy: AccessPolicyName,
    roleOrAction: Either[ResourceRoleName, ResourceAction],
    isPublic: Boolean,
    inherited: Boolean
)
