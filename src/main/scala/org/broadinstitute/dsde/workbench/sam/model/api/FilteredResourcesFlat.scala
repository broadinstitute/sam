package org.broadinstitute.dsde.workbench.sam.model.api

import org.broadinstitute.dsde.workbench.model.WorkbenchGroupName
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicyName, ResourceAction, ResourceId, ResourceRoleName, ResourceTypeName}
import spray.json.DefaultJsonProtocol.jsonFormat1
import spray.json.RootJsonFormat
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._
import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import spray.json.DefaultJsonProtocol._

object FilteredResourcesFlat {
  implicit val FilteredResourcesFlatFormat: RootJsonFormat[FilteredResourcesFlat] = jsonFormat1(FilteredResourcesFlat.apply)

}
case class FilteredResourcesFlat(resources: Set[FilteredResourceFlat]) extends FilteredResources {
  override def format: String = "flat"
}

object FilteredResourceFlat {
  implicit val FilteredResourceFlatFormat: RootJsonFormat[FilteredResourceFlat] = jsonFormat7(FilteredResourceFlat.apply)

}
case class FilteredResourceFlat(
    resourceType: ResourceTypeName,
    resourceId: ResourceId,
    policies: Set[FilteredResourceFlatPolicy],
    roles: Set[ResourceRoleName],
    actions: Set[ResourceAction],
    authDomainGroups: Set[WorkbenchGroupName],
    missingAuthDomainGroups: Set[WorkbenchGroupName]
)

case object FilteredResourceFlatPolicy {
  implicit val filteredResourceFlatPolicyFormat: RootJsonFormat[FilteredResourceFlatPolicy] = jsonFormat3(
    FilteredResourceFlatPolicy.apply
  )
}
case class FilteredResourceFlatPolicy(
    policy: AccessPolicyName,
    isPublic: Boolean,
    inherited: Boolean
)
