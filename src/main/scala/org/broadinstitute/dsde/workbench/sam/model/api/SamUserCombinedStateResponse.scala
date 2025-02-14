package org.broadinstitute.dsde.workbench.sam.model.api

import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport.WorkbenchGroupNameFormat
import org.broadinstitute.dsde.workbench.model.{WorkbenchGroupIdentity, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedPolicyId, FullyQualifiedResourceId, PolicyIdentifiers, TermsOfServiceDetails}
import spray.json.DefaultJsonProtocol._
import spray.json._
import org.broadinstitute.dsde.workbench.sam.model.api.GroupMembershipCounts.GroupMembershipCountsFormat
import org.broadinstitute.dsde.workbench.sam.model.api.SamUserAllowances.SamUserAllowedResponseFormat
import org.broadinstitute.dsde.workbench.sam.model.api.SamUserAttributes.SamUserAttributesFormat
import org.broadinstitute.dsde.workbench.sam.model.api.SamJsonSupport._

object SamUserCombinedStateResponse {
  implicit val GroupMembershipCountFormat: RootJsonFormat[GroupMembershipCount] = jsonFormat3(GroupMembershipCount.apply)
  implicit val SamUserResponseFormat: RootJsonFormat[SamUserCombinedStateResponse] = jsonFormat8(SamUserCombinedStateResponse.apply)
}

final case class GroupMembershipCount(
    group: Option[WorkbenchGroupName],
    policy: Option[PolicyIdentifiers],
    attributedMembershipCount: Int
)
object GroupMembershipCount {
  def apply(group: WorkbenchGroupIdentity, attributedMembershipCount: Int): GroupMembershipCount =
    group match {
      case groupName: WorkbenchGroupName => GroupMembershipCount(Option(groupName), None, attributedMembershipCount)
      case policyId: FullyQualifiedPolicyId =>
        GroupMembershipCount(
          None,
          Option(PolicyIdentifiers(policyId.accessPolicyName, policyId.resource.resourceTypeName, policyId.resource.resourceId)),
          attributedMembershipCount
        )
    }
}

final case class SamUserCombinedStateResponse(
    samUser: SamUser,
    allowances: SamUserAllowances,
    attributes: Option[SamUserAttributes],
    termsOfServiceDetails: TermsOfServiceDetails,
    groupMembershipCounts: GroupMembershipCounts,
    additionalDetails: Map[String, JsValue],
    favoriteResources: Set[FullyQualifiedResourceId],
    groupsContributingToMostMemberships: Option[List[GroupMembershipCount]]
)
