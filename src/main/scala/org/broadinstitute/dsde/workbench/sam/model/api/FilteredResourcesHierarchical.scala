package org.broadinstitute.dsde.workbench.sam.model.api

import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

object FilteredResourcesHierarchical {
  implicit val FilteredResourcesHierarchicalFormat: RootJsonFormat[FilteredResourcesHierarchical] = jsonFormat1(FilteredResourcesHierarchical.apply)

}
case class FilteredResourcesHierarchical(resources: Set[FilteredResourceHierarchical]) extends FilteredResources
case object FilteredResourceHierarchicalPolicy {
  implicit val filteredResourceHierarchicalPolicyFormat: RootJsonFormat[FilteredResourceHierarchicalPolicy] = jsonFormat4(
    FilteredResourceHierarchicalPolicy.apply
  )
}
case class FilteredResourceHierarchicalPolicy(
    policy: AccessPolicyName,
    roles: Set[FilteredResourceHierarchicalRole],
    actions: Set[ResourceAction],
    isPublic: Boolean
)

case object FilteredResourceHierarchicalRole {
  implicit val filteredResourceHierarchicalRoleFormat: RootJsonFormat[FilteredResourceHierarchicalRole] = jsonFormat2(FilteredResourceHierarchicalRole.apply)

}
case class FilteredResourceHierarchicalRole(role: ResourceRoleName, actions: Set[ResourceAction])

case object FilteredResourceHierarchical {
  implicit val filteredResourceHierarchicalFormat: RootJsonFormat[FilteredResourceHierarchical] = jsonFormat3(FilteredResourceHierarchical.apply)

}
case class FilteredResourceHierarchical(
    resourceType: ResourceTypeName,
    resourceId: ResourceId,
    policies: Set[FilteredResourceHierarchicalPolicy]
)
