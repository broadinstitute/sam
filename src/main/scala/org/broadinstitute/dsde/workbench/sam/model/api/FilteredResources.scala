package org.broadinstitute.dsde.workbench.sam.model.api

import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicyName, ResourceAction, ResourceId, ResourceRoleName, ResourceTypeName}

case class FilteredResources(
    resourceTypeName: ResourceTypeName,
    resources: Seq[FilteredResource])


case class FilteredResource(
    resourceId: ResourceId,
    policies: Seq[AccessPolicyName],
    roles: Seq[ResourceRoleName],
    actions: Seq[ResourceAction])
