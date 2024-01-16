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
  implicit val FilteredResourceFlatFormat: RootJsonFormat[FilteredResourceFlat] = jsonFormat9(FilteredResourceFlat.apply)

}
case class FilteredResourceFlat(
    resourceType: ResourceTypeName,
    resourceId: ResourceId,
    policies: Set[AccessPolicyName],
    roles: Set[ResourceRoleName],
    actions: Set[ResourceAction],
    isPublic: Boolean,
    inherited: Boolean,
    authDomainGroups: Set[WorkbenchGroupName],
    missingAuthDomainGroups: Set[WorkbenchGroupName]
)
