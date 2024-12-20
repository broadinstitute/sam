package org.broadinstitute.dsde.workbench.sam.model.api

import org.broadinstitute.dsde.workbench.model.WorkbenchEmail
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicyName, PolicyIdentifiers, ResourceId, ResourceTypeName}
import spray.json.RootJsonFormat

case class PolicyMembershipUpdate(
    policyName: AccessPolicyName,
    addEmails: Set[WorkbenchEmail],
    addPolicies: Set[PolicyIdentifiers],
    removeEmails: Set[WorkbenchEmail],
    removePolicies: Set[PolicyIdentifiers]
)
object PolicyMembershipUpdate {
  import spray.json.DefaultJsonProtocol._
  import SamJsonSupport._
  import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
  implicit val policyMembershipUpdateFormat: RootJsonFormat[PolicyMembershipUpdate] = jsonFormat5(PolicyMembershipUpdate.apply)
}

case class BulkMembershipUpdate(resourceTypeName: ResourceTypeName, resourceId: ResourceId, policyUpdates: Seq[PolicyMembershipUpdate])
object BulkMembershipUpdate {
  import spray.json.DefaultJsonProtocol._
  import SamJsonSupport._
  import PolicyMembershipUpdate.policyMembershipUpdateFormat
  implicit val bulkMembershipUpdateFormat: RootJsonFormat[BulkMembershipUpdate] = jsonFormat3(BulkMembershipUpdate.apply)
}
