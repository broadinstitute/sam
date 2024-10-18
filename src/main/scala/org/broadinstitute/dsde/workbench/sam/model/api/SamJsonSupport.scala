package org.broadinstitute.dsde.workbench.sam.model.api

import org.broadinstitute.dsde.workbench.model.ValueObjectFormat
import org.broadinstitute.dsde.workbench.sam.model.{
  AccessPolicyDescendantPermissions,
  AccessPolicyName,
  AccessPolicyResponseEntry,
  CreateResourcePolicyResponse,
  CreateResourceRequest,
  CreateResourceResponse,
  FullyQualifiedPolicyId,
  FullyQualifiedResourceId,
  GroupSyncResponse,
  OldTermsOfServiceDetails,
  PolicyIdentifiers,
  RequesterPaysSignedUrlRequest,
  ResourceAction,
  ResourceActionPattern,
  ResourceId,
  ResourceRole,
  ResourceRoleName,
  ResourceType,
  ResourceTypeName,
  RolesAndActions,
  SamUserTos,
  SignedUrlRequest,
  TermsOfServiceAcceptance,
  TermsOfServiceComplianceStatus,
  TermsOfServiceDetails,
  TermsOfServiceHistory,
  TermsOfServiceHistoryRecord,
  UserIdInfo,
  UserPolicyResponse,
  UserResourcesResponse,
  UserStatus,
  UserStatusDetails,
  UserStatusDiagnostics,
  UserStatusInfo
}
import org.broadinstitute.dsde.workbench.model.google.GoogleModelJsonSupport.InstantFormat
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import org.broadinstitute.dsde.workbench.sam.model.api.SamApiJsonProtocol.PolicyInfoResponseBodyJsonFormat

object SamJsonSupport {
  import DefaultJsonProtocol._
  import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._

  implicit val ResourceActionPatternFormat: RootJsonFormat[ResourceActionPattern] = jsonFormat3(ResourceActionPattern.apply)

  implicit val ResourceActionFormat: ValueObjectFormat[ResourceAction] = ValueObjectFormat(ResourceAction.apply)

  implicit val ResourceRoleNameFormat: ValueObjectFormat[ResourceRoleName] = ValueObjectFormat(ResourceRoleName.apply)

  implicit val ResourceTypeNameFormat: ValueObjectFormat[ResourceTypeName] = ValueObjectFormat(ResourceTypeName.apply)

  implicit val ResourceRoleFormat: RootJsonFormat[ResourceRole] = jsonFormat4(ResourceRole.apply)

  implicit val ResourceTypeFormat: RootJsonFormat[ResourceType] = jsonFormat6(ResourceType.apply)

  implicit val SamUserFormat: RootJsonFormat[SamUser] = jsonFormat8(SamUser.apply)

  implicit val UserStatusDetailsFormat: RootJsonFormat[UserStatusDetails] = jsonFormat2(UserStatusDetails.apply)

  implicit val UserStatusFormat: RootJsonFormat[UserStatus] = jsonFormat2(UserStatus.apply)

  implicit val UserStatusInfoFormat: RootJsonFormat[UserStatusInfo] = jsonFormat4(UserStatusInfo.apply)

  implicit val UserIdInfoFormat: RootJsonFormat[UserIdInfo] = jsonFormat3(UserIdInfo.apply)

  implicit val TermsOfServiceAcceptanceFormat: ValueObjectFormat[TermsOfServiceAcceptance] = ValueObjectFormat(TermsOfServiceAcceptance.apply)

  implicit val oldTermsOfServiceDetailsFormat: RootJsonFormat[OldTermsOfServiceDetails] = jsonFormat4(OldTermsOfServiceDetails.apply)

  implicit val TermsOfServiceDetailsFormat: RootJsonFormat[TermsOfServiceDetails] = jsonFormat4(TermsOfServiceDetails.apply)

  implicit val termsOfServiceHistoryRecordFormat: RootJsonFormat[TermsOfServiceHistoryRecord] = jsonFormat3(TermsOfServiceHistoryRecord.apply)

  implicit val termsOfServiceHistory: RootJsonFormat[TermsOfServiceHistory] = jsonFormat1(TermsOfServiceHistory.apply)

  implicit val SamUserTosFormat: RootJsonFormat[SamUserTos] = jsonFormat4(SamUserTos.apply)

  implicit val termsOfAcceptanceStatusFormat: RootJsonFormat[TermsOfServiceComplianceStatus] = jsonFormat3(TermsOfServiceComplianceStatus.apply)

  implicit val UserStatusDiagnosticsFormat: RootJsonFormat[UserStatusDiagnostics] = jsonFormat5(UserStatusDiagnostics.apply)

  implicit val AccessPolicyNameFormat: ValueObjectFormat[AccessPolicyName] = ValueObjectFormat(AccessPolicyName.apply)

  implicit val ResourceIdFormat: ValueObjectFormat[ResourceId] = ValueObjectFormat(ResourceId.apply)

  implicit val FullyQualifiedResourceIdFormat: RootJsonFormat[FullyQualifiedResourceId] = jsonFormat2(FullyQualifiedResourceId.apply)

  implicit val AccessPolicyDescendantPermissionsFormat: RootJsonFormat[AccessPolicyDescendantPermissions] = jsonFormat3(AccessPolicyDescendantPermissions.apply)

  implicit val PolicyIdentifiersFormat: RootJsonFormat[PolicyIdentifiers] = jsonFormat3(PolicyIdentifiers.apply)

  implicit val AccessPolicyMembershipResponseFormat: RootJsonFormat[AccessPolicyMembershipResponse] = jsonFormat5(AccessPolicyMembershipResponse.apply)

  implicit val AccessPolicyResponseEntryFormat: RootJsonFormat[AccessPolicyResponseEntry] = jsonFormat3(AccessPolicyResponseEntry.apply)

  implicit val UserPolicyResponseFormat: RootJsonFormat[UserPolicyResponse] = jsonFormat5(UserPolicyResponse.apply)

  implicit val RolesAndActionsFormat: RootJsonFormat[RolesAndActions] = jsonFormat2(RolesAndActions.apply)

  implicit val UserResourcesResponseFormat: RootJsonFormat[UserResourcesResponse] = jsonFormat6(UserResourcesResponse.apply)

  implicit val FullyQualifiedPolicyIdFormat: RootJsonFormat[FullyQualifiedPolicyId] = jsonFormat2(FullyQualifiedPolicyId.apply)

  implicit val GroupSyncResponseFormat: RootJsonFormat[GroupSyncResponse] = jsonFormat2(GroupSyncResponse.apply)

  implicit val AccessPolicyMembershipRequestFormat: RootJsonFormat[AccessPolicyMembershipRequest] = jsonFormat5(AccessPolicyMembershipRequest.apply)

  implicit val CreateResourceRequestFormat: RootJsonFormat[CreateResourceRequest] = jsonFormat5(CreateResourceRequest.apply)

  implicit val CreateResourcePolicyResponseFormat: RootJsonFormat[CreateResourcePolicyResponse] = jsonFormat2(CreateResourcePolicyResponse.apply)

  implicit val CreateResourceResponseFormat: RootJsonFormat[CreateResourceResponse] = jsonFormat4(CreateResourceResponse.apply)

  implicit val SignedUrlRequestFormat: RootJsonFormat[SignedUrlRequest] = jsonFormat4(SignedUrlRequest.apply)

  implicit val RequesterPaysSignedUrlRequestFormat: RootJsonFormat[RequesterPaysSignedUrlRequest] = jsonFormat3(RequesterPaysSignedUrlRequest.apply)
}
