package org.broadinstitute.dsde.workbench.sam.model.api

import monocle.macros.Lenses
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model._
import spray.json.{DefaultJsonProtocol, DeserializationException, JsArray, JsString, JsValue, RootJsonFormat, deserializationError}

// AccessPolicyMembership.memberPolicies is logically read-only; at some point in the future it could be lazy-loaded
// (via extra queries) based on the contents of memberEmails.
@Lenses final case class AccessPolicyMembershipResponse(
    memberEmails: Set[WorkbenchEmail],
    actions: Set[ResourceAction],
    roles: Set[ResourceRoleName],
    descendantPermissions: Option[Set[AccessPolicyDescendantPermissions]] = Option(Set.empty),
    memberPolicies: Option[Set[PolicyInfoResponseBody]] = Option(Set.empty)
) {
  def getDescendantPermissions: Set[AccessPolicyDescendantPermissions] = descendantPermissions.getOrElse(Set.empty)
}

@Lenses final case class AccessPolicyMembershipRequest(
    memberEmails: Set[WorkbenchEmail],
    actions: Set[ResourceAction],
    roles: Set[ResourceRoleName],
    descendantPermissions: Option[Set[AccessPolicyDescendantPermissions]] = Option(Set.empty),
    memberPolicies: Option[Set[PolicyIdentifiers]] = Option(Set.empty)
) {
  def getDescendantPermissions: Set[AccessPolicyDescendantPermissions] = descendantPermissions.getOrElse(Set.empty)
}
@Lenses final case class PolicyInfoResponseBody(
    policyIdentifiers: PolicyIdentifiers,
    policyEmail: WorkbenchEmail
)
object PolicyInfoResponseBody {
  def apply(
      accessPolicyName: AccessPolicyName,
      policyEmail: WorkbenchEmail,
      resourceTypeName: ResourceTypeName,
      resourceId: ResourceId
  ): PolicyInfoResponseBody =
    PolicyInfoResponseBody(PolicyIdentifiers(accessPolicyName, resourceTypeName, resourceId), policyEmail)

}

object SamApiJsonProtocol extends DefaultJsonProtocol {
  implicit object PolicyInfoResponseBodyJsonFormat extends RootJsonFormat[PolicyInfoResponseBody] {
    def write(p: PolicyInfoResponseBody) =
      JsArray(
        JsString(p.policyIdentifiers.policyName.value),
        JsString(p.policyEmail.value),
        JsString(p.policyIdentifiers.resourceTypeName.value),
        JsString(p.policyIdentifiers.resourceId.value)
      )

    def read(value: JsValue): PolicyInfoResponseBody = value match {
      case JsArray(Vector(JsString(policyName), JsString(policyEmail), JsString(resourceTypeName), JsString(resourceId))) =>
        PolicyInfoResponseBody(AccessPolicyName(policyName), WorkbenchEmail(policyEmail), ResourceTypeName(resourceTypeName), ResourceId(resourceId))
      case _ => deserializationError("PolicyInfoResponseBody expected; Could not deserialize JSON")
    }
  }
}
