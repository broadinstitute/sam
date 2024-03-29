package org.broadinstitute.dsde.workbench.sam.db

import java.sql.ResultSet
import org.broadinstitute.dsde.workbench.model.google.{GoogleProject, ServiceAccountDisplayName, ServiceAccountSubjectId}
import org.broadinstitute.dsde.workbench.model.{GoogleSubjectId, WorkbenchEmail, WorkbenchGroupName, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.azure.{
  BillingProfileId,
  ManagedIdentityDisplayName,
  ManagedIdentityObjectId,
  ManagedResourceGroupName,
  SubscriptionId,
  TenantId
}
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.model._
import scalikejdbc.TypeBinder

object SamTypeBinders {
  implicit val accessInstructionsPKTypeBinder: TypeBinder[AccessInstructionsPK] = new TypeBinder[AccessInstructionsPK] {
    def apply(rs: ResultSet, label: String): AccessInstructionsPK = AccessInstructionsPK(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): AccessInstructionsPK = AccessInstructionsPK(rs.getLong(index))
  }

  implicit val groupMemberPKTypeBinder: TypeBinder[GroupMemberPK] = new TypeBinder[GroupMemberPK] {
    def apply(rs: ResultSet, label: String): GroupMemberPK = GroupMemberPK(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): GroupMemberPK = GroupMemberPK(rs.getLong(index))
  }

  implicit val groupPKTypeBinder: TypeBinder[GroupPK] = new TypeBinder[GroupPK] {
    def apply(rs: ResultSet, label: String): GroupPK = GroupPK(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): GroupPK = GroupPK(rs.getLong(index))
  }

  implicit val policyPKTypeBinder: TypeBinder[PolicyPK] = new TypeBinder[PolicyPK] {
    def apply(rs: ResultSet, label: String): PolicyPK = PolicyPK(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): PolicyPK = PolicyPK(rs.getLong(index))
  }

  implicit val effectivePolicyPKTypeBinder: TypeBinder[EffectiveResourcePolicyPK] = new TypeBinder[EffectiveResourcePolicyPK] {
    def apply(rs: ResultSet, label: String): EffectiveResourcePolicyPK = EffectiveResourcePolicyPK(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): EffectiveResourcePolicyPK = EffectiveResourcePolicyPK(rs.getLong(index))
  }

  implicit val policyNameTypeBinder: TypeBinder[AccessPolicyName] = new TypeBinder[AccessPolicyName] {
    def apply(rs: ResultSet, label: String): AccessPolicyName = AccessPolicyName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): AccessPolicyName = AccessPolicyName(rs.getString(index))
  }

  implicit val resourceActionPatternPKTypeBinder: TypeBinder[ResourceActionPatternPK] = new TypeBinder[ResourceActionPatternPK] {
    def apply(rs: ResultSet, label: String): ResourceActionPatternPK = ResourceActionPatternPK(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): ResourceActionPatternPK = ResourceActionPatternPK(rs.getLong(index))
  }

  implicit val resourceActionPatternNameTypeBinder: TypeBinder[ResourceActionPatternName] = new TypeBinder[ResourceActionPatternName] {
    def apply(rs: ResultSet, label: String): ResourceActionPatternName = ResourceActionPatternName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ResourceActionPatternName = ResourceActionPatternName(rs.getString(index))
  }

  implicit val resourceActionPKTypeBinder: TypeBinder[ResourceActionPK] = new TypeBinder[ResourceActionPK] {
    def apply(rs: ResultSet, label: String): ResourceActionPK = ResourceActionPK(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): ResourceActionPK = ResourceActionPK(rs.getLong(index))
  }

  implicit val resourceActionNameTypeBinder: TypeBinder[ResourceAction] = new TypeBinder[ResourceAction] {
    def apply(rs: ResultSet, label: String): ResourceAction = ResourceAction(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ResourceAction = ResourceAction(rs.getString(index))
  }

  implicit val resourceRolePKTypeBinder: TypeBinder[ResourceRolePK] = new TypeBinder[ResourceRolePK] {
    def apply(rs: ResultSet, label: String): ResourceRolePK = ResourceRolePK(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): ResourceRolePK = ResourceRolePK(rs.getLong(index))
  }

  implicit val resourceRoleNameTypeBinder: TypeBinder[ResourceRoleName] = new TypeBinder[ResourceRoleName] {
    def apply(rs: ResultSet, label: String): ResourceRoleName = ResourceRoleName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ResourceRoleName = ResourceRoleName(rs.getString(index))
  }

  implicit val resourcePKTypeBinder: TypeBinder[ResourcePK] = new TypeBinder[ResourcePK] {
    def apply(rs: ResultSet, label: String): ResourcePK = ResourcePK(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): ResourcePK = ResourcePK(rs.getLong(index))
  }

  implicit val resourceIdTypeBinder: TypeBinder[ResourceId] = new TypeBinder[ResourceId] {
    def apply(rs: ResultSet, label: String): ResourceId = ResourceId(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ResourceId = ResourceId(rs.getString(index))
  }

  implicit val resourceTypePKTypeBinder: TypeBinder[ResourceTypePK] = new TypeBinder[ResourceTypePK] {
    def apply(rs: ResultSet, label: String): ResourceTypePK = ResourceTypePK(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): ResourceTypePK = ResourceTypePK(rs.getLong(index))
  }

  implicit val resourceTypeNameTypeBinder: TypeBinder[ResourceTypeName] = new TypeBinder[ResourceTypeName] {
    def apply(rs: ResultSet, label: String): ResourceTypeName = ResourceTypeName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ResourceTypeName = ResourceTypeName(rs.getString(index))
  }

  implicit val workbenchGroupNameTypeBinder: TypeBinder[WorkbenchGroupName] = new TypeBinder[WorkbenchGroupName] {
    def apply(rs: ResultSet, label: String): WorkbenchGroupName = WorkbenchGroupName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): WorkbenchGroupName = WorkbenchGroupName(rs.getString(index))
  }

  implicit val workbenchEmailTypeBinder: TypeBinder[WorkbenchEmail] = new TypeBinder[WorkbenchEmail] {
    def apply(rs: ResultSet, label: String): WorkbenchEmail = WorkbenchEmail(rs.getString(label))
    def apply(rs: ResultSet, index: Int): WorkbenchEmail = WorkbenchEmail(rs.getString(index))
  }

  implicit val googleProjectTypeBinder: TypeBinder[GoogleProject] = new TypeBinder[GoogleProject] {
    def apply(rs: ResultSet, label: String): GoogleProject = GoogleProject(rs.getString(label))
    def apply(rs: ResultSet, index: Int): GoogleProject = GoogleProject(rs.getString(index))
  }

  implicit val googleSubjectIdTypeBinder: TypeBinder[GoogleSubjectId] = new TypeBinder[GoogleSubjectId] {
    def apply(rs: ResultSet, label: String): GoogleSubjectId = GoogleSubjectId(rs.getString(label))
    def apply(rs: ResultSet, index: Int): GoogleSubjectId = GoogleSubjectId(rs.getString(index))
  }

  implicit val workbenchUserIdTypeBinder: TypeBinder[WorkbenchUserId] = new TypeBinder[WorkbenchUserId] {
    def apply(rs: ResultSet, label: String): WorkbenchUserId = WorkbenchUserId(rs.getString(label))
    def apply(rs: ResultSet, index: Int): WorkbenchUserId = WorkbenchUserId(rs.getString(index))
  }

  implicit val serviceAccountDisplayNameTypeBinder: TypeBinder[ServiceAccountDisplayName] = new TypeBinder[ServiceAccountDisplayName] {
    def apply(rs: ResultSet, label: String): ServiceAccountDisplayName = ServiceAccountDisplayName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ServiceAccountDisplayName = ServiceAccountDisplayName(rs.getString(index))
  }

  implicit val serviceAccountSubjectIdTypeBinder: TypeBinder[ServiceAccountSubjectId] = new TypeBinder[ServiceAccountSubjectId] {
    def apply(rs: ResultSet, label: String): ServiceAccountSubjectId = ServiceAccountSubjectId(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ServiceAccountSubjectId = ServiceAccountSubjectId(rs.getString(index))
  }

  implicit val flatGroupMemberPKTypeBinder: TypeBinder[GroupMemberFlatPK] = new TypeBinder[GroupMemberFlatPK] {
    def apply(rs: ResultSet, label: String): GroupMemberFlatPK = GroupMemberFlatPK(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): GroupMemberFlatPK = GroupMemberFlatPK(rs.getLong(index))
  }

  implicit val flatGroupMembershipPathPKTypeBinder: TypeBinder[GroupMembershipPath] = new TypeBinder[GroupMembershipPath] {
    def apply(rs: ResultSet, label: String): GroupMembershipPath =
      GroupMembershipPath(rs.getArray(label).getArray.asInstanceOf[Array[java.lang.Long]].map(_.longValue()).toList.map(GroupPK))
    def apply(rs: ResultSet, index: Int): GroupMembershipPath =
      GroupMembershipPath(rs.getArray(index).getArray.asInstanceOf[Array[java.lang.Long]].map(_.longValue()).toList.map(GroupPK))
  }

  implicit val tenantIdTypeBinder: TypeBinder[TenantId] = new TypeBinder[TenantId] {
    def apply(rs: ResultSet, label: String): TenantId = TenantId(rs.getString(label))
    def apply(rs: ResultSet, index: Int): TenantId = TenantId(rs.getString(index))
  }

  implicit val subscriptionIdTypeBinder: TypeBinder[SubscriptionId] = new TypeBinder[SubscriptionId] {
    def apply(rs: ResultSet, label: String): SubscriptionId = SubscriptionId(rs.getString(label))
    def apply(rs: ResultSet, index: Int): SubscriptionId = SubscriptionId(rs.getString(index))
  }

  implicit val managedResourceGroupNameTypeBinder: TypeBinder[ManagedResourceGroupName] = new TypeBinder[ManagedResourceGroupName] {
    def apply(rs: ResultSet, label: String): ManagedResourceGroupName = ManagedResourceGroupName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ManagedResourceGroupName = ManagedResourceGroupName(rs.getString(index))
  }

  implicit val managedIdentityObjectIdTypeBinder: TypeBinder[ManagedIdentityObjectId] = new TypeBinder[ManagedIdentityObjectId] {
    def apply(rs: ResultSet, label: String): ManagedIdentityObjectId = ManagedIdentityObjectId(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ManagedIdentityObjectId = ManagedIdentityObjectId(rs.getString(index))
  }

  implicit val managedIdentityDisplayNameTypeBinder: TypeBinder[ManagedIdentityDisplayName] = new TypeBinder[ManagedIdentityDisplayName] {
    def apply(rs: ResultSet, label: String): ManagedIdentityDisplayName = ManagedIdentityDisplayName(rs.getString(label))
    def apply(rs: ResultSet, index: Int): ManagedIdentityDisplayName = ManagedIdentityDisplayName(rs.getString(index))
  }

  implicit val ManagedResourceGroupPKTypeBinder: TypeBinder[ManagedResourceGroupPK] = new TypeBinder[ManagedResourceGroupPK] {
    def apply(rs: ResultSet, label: String): ManagedResourceGroupPK = ManagedResourceGroupPK(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): ManagedResourceGroupPK = ManagedResourceGroupPK(rs.getLong(index))
  }

  implicit val BillingProfileIdTypeBinder: TypeBinder[BillingProfileId] = new TypeBinder[BillingProfileId] {
    def apply(rs: ResultSet, label: String): BillingProfileId = BillingProfileId(rs.getString(label))
    def apply(rs: ResultSet, index: Int): BillingProfileId = BillingProfileId(rs.getString(index))
  }

  implicit val lastQuotaErrorPKTypeBinder: TypeBinder[LastQuotaErrorPK] = new TypeBinder[LastQuotaErrorPK] {
    def apply(rs: ResultSet, label: String): LastQuotaErrorPK = LastQuotaErrorPK(rs.getLong(label))
    def apply(rs: ResultSet, index: Int): LastQuotaErrorPK = LastQuotaErrorPK(rs.getLong(index))
  }

}
