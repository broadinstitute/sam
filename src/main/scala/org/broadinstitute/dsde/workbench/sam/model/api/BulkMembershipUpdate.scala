package org.broadinstitute.dsde.workbench.sam.model.api

import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.model.{AccessPolicyName, PolicyIdentifiers, ResourceId, ResourceTypeName}
import spray.json.RootJsonFormat

case class PolicyMembershipUpdate(
    policyName: AccessPolicyName,
    addUserIds: Set[WorkbenchUserId] = Set.empty,
    addEmails: Set[WorkbenchEmail] = Set.empty,
    addPolicies: Set[PolicyIdentifiers] = Set.empty,
    removeUserIds: Set[WorkbenchUserId] = Set.empty,
    removeEmails: Set[WorkbenchEmail] = Set.empty,
    removePolicies: Set[PolicyIdentifiers] = Set.empty
)
object PolicyMembershipUpdate {
  import spray.json.DefaultJsonProtocol._
  import SamJsonSupport._
  import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
  implicit val policyMembershipUpdateFormat: RootJsonFormat[PolicyMembershipUpdate] = jsonFormat7(PolicyMembershipUpdate.apply)
}

case class BulkMembershipUpdate(resourceTypeName: ResourceTypeName, resourceId: ResourceId, policyUpdates: Seq[PolicyMembershipUpdate])
object BulkMembershipUpdate {
  import spray.json.DefaultJsonProtocol._
  import SamJsonSupport._
  import PolicyMembershipUpdate.policyMembershipUpdateFormat
  implicit val bulkMembershipUpdateFormat: RootJsonFormat[BulkMembershipUpdate] = jsonFormat3(BulkMembershipUpdate.apply)
}
