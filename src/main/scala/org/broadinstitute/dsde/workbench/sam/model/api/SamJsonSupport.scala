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
  ManagedGroupAccessInstructions,
  ManagedGroupMembershipEntry,
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

  implicit val ResourceActionPatternFormat = jsonFormat3(ResourceActionPattern.apply)

  implicit val ResourceActionFormat = ValueObjectFormat(ResourceAction.apply)

  implicit val ResourceRoleNameFormat = ValueObjectFormat(ResourceRoleName.apply)

  implicit val ResourceTypeNameFormat = ValueObjectFormat(ResourceTypeName.apply)

  implicit val ResourceRoleFormat = jsonFormat4(ResourceRole.apply)

  implicit val ResourceTypeFormat = jsonFormat6(ResourceType.apply)

  implicit val SamUserFormat = jsonFormat8(SamUser.apply)

  implicit val UserStatusDetailsFormat = jsonFormat2(UserStatusDetails.apply)

  implicit val UserStatusFormat = jsonFormat2(UserStatus.apply)

  implicit val UserStatusInfoFormat = jsonFormat4(UserStatusInfo.apply)

  implicit val UserIdInfoFormat = jsonFormat3(UserIdInfo.apply)

  implicit val TermsOfServiceAcceptanceFormat = ValueObjectFormat(TermsOfServiceAcceptance.apply)

  implicit val oldTermsOfServiceDetailsFormat = jsonFormat4(OldTermsOfServiceDetails.apply)

  implicit val TermsOfServiceDetailsFormat = jsonFormat4(TermsOfServiceDetails.apply)

  implicit val termsOfServiceHistoryRecordFormat = jsonFormat3(TermsOfServiceHistoryRecord.apply)

  implicit val termsOfServiceHistory = jsonFormat1(TermsOfServiceHistory.apply)

  implicit val SamUserTosFormat = jsonFormat4(SamUserTos.apply)

  implicit val termsOfAcceptanceStatusFormat = jsonFormat3(TermsOfServiceComplianceStatus.apply)

  implicit val UserStatusDiagnosticsFormat = jsonFormat5(UserStatusDiagnostics.apply)

  implicit val AccessPolicyNameFormat = ValueObjectFormat(AccessPolicyName.apply)

  implicit val ResourceIdFormat = ValueObjectFormat(ResourceId.apply)

  implicit val FullyQualifiedResourceIdFormat = jsonFormat2(FullyQualifiedResourceId.apply)

  implicit val AccessPolicyDescendantPermissionsFormat: RootJsonFormat[AccessPolicyDescendantPermissions] = jsonFormat3(AccessPolicyDescendantPermissions.apply)

  implicit val PolicyIdentifiersFormat = jsonFormat3(PolicyIdentifiers.apply)

  implicit val AccessPolicyMembershipResponseFormat = jsonFormat5(AccessPolicyMembershipResponse.apply)

  implicit val AccessPolicyResponseEntryFormat = jsonFormat3(AccessPolicyResponseEntry.apply)

  implicit val UserPolicyResponseFormat = jsonFormat5(UserPolicyResponse.apply)

  implicit val RolesAndActionsFormat = jsonFormat2(RolesAndActions.apply)

  implicit val UserResourcesResponseFormat = jsonFormat6(UserResourcesResponse.apply)

  implicit val FullyQualifiedPolicyIdFormat = jsonFormat2(FullyQualifiedPolicyId.apply)

  implicit val ManagedGroupMembershipEntryFormat = jsonFormat3(ManagedGroupMembershipEntry.apply)

  implicit val ManagedGroupAccessInstructionsFormat = ValueObjectFormat(ManagedGroupAccessInstructions.apply)

  implicit val GroupSyncResponseFormat = jsonFormat2(GroupSyncResponse.apply)

  implicit val AccessPolicyMembershipRequestFormat = jsonFormat5(AccessPolicyMembershipRequest.apply)

  implicit val CreateResourceRequestFormat = jsonFormat5(CreateResourceRequest.apply)

  implicit val CreateResourcePolicyResponseFormat = jsonFormat2(CreateResourcePolicyResponse.apply)

  implicit val CreateResourceResponseFormat = jsonFormat4(CreateResourceResponse.apply)

  implicit val SignedUrlRequestFormat = jsonFormat4(SignedUrlRequest.apply)

  implicit val RequesterPaysSignedUrlRequestFormat = jsonFormat3(RequesterPaysSignedUrlRequest.apply)
}
