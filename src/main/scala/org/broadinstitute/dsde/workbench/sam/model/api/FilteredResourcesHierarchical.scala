package org.broadinstitute.dsde.workbench.sam.model.api

import org.broadinstitute.dsde.workbench.model.WorkbenchGroupName
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._

object FilteredResourcesHierarchical {
  implicit val FilteredResourcesHierarchicalFormat: RootJsonFormat[FilteredResourcesHierarchical] = jsonFormat2(FilteredResourcesHierarchical.apply)

}
case class FilteredResourcesHierarchical(resources: Set[FilteredResourceHierarchical], format: String = "hierarchical") extends FilteredResources

case object FilteredResourceHierarchicalPolicy {
  implicit val filteredResourceHierarchicalPolicyFormat: RootJsonFormat[FilteredResourceHierarchicalPolicy] = jsonFormat5(
    FilteredResourceHierarchicalPolicy.apply
  )
}
case class FilteredResourceHierarchicalPolicy(
    policy: AccessPolicyName,
    roles: Set[FilteredResourceHierarchicalRole],
    actions: Set[ResourceAction],
    isPublic: Boolean,
    inherited: Boolean
)

case object FilteredResourceHierarchicalRole {
  implicit val filteredResourceHierarchicalRoleFormat: RootJsonFormat[FilteredResourceHierarchicalRole] = jsonFormat2(FilteredResourceHierarchicalRole.apply)

}
case class FilteredResourceHierarchicalRole(role: ResourceRoleName, actions: Set[ResourceAction])

case object FilteredResourceHierarchical {
  implicit val filteredResourceHierarchicalFormat: RootJsonFormat[FilteredResourceHierarchical] = jsonFormat5(FilteredResourceHierarchical.apply)

}
case class FilteredResourceHierarchical(
    resourceType: ResourceTypeName,
    resourceId: ResourceId,
    policies: Set[FilteredResourceHierarchicalPolicy],
    authDomainGroups: Set[WorkbenchGroupName],
    missingAuthDomainGroups: Set[WorkbenchGroupName]
)
