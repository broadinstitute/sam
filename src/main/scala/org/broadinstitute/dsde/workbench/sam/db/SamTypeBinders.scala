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
    def apply(rs: ResultSet, label: String): AccessInstructionsPK = nullSafe(rs.getLong(label), AccessInstructionsPK)
    def apply(rs: ResultSet, index: Int): AccessInstructionsPK = nullSafe(rs.getLong(index), AccessInstructionsPK)
  }

  implicit val groupMemberPKTypeBinder: TypeBinder[GroupMemberPK] = new TypeBinder[GroupMemberPK] {
    def apply(rs: ResultSet, label: String): GroupMemberPK = nullSafe(rs.getLong(label), GroupMemberPK)
    def apply(rs: ResultSet, index: Int): GroupMemberPK = nullSafe(rs.getLong(index), GroupMemberPK)
  }

  implicit val groupPKTypeBinder: TypeBinder[GroupPK] = new TypeBinder[GroupPK] {
    def apply(rs: ResultSet, label: String): GroupPK = nullSafe(rs.getLong(label), GroupPK)
    def apply(rs: ResultSet, index: Int): GroupPK = nullSafe(rs.getLong(index), GroupPK)
  }

  implicit val policyPKTypeBinder: TypeBinder[PolicyPK] = new TypeBinder[PolicyPK] {
    def apply(rs: ResultSet, label: String): PolicyPK = nullSafe(rs.getLong(label), PolicyPK)
    def apply(rs: ResultSet, index: Int): PolicyPK = nullSafe(rs.getLong(index), PolicyPK)
  }

  implicit val effectivePolicyPKTypeBinder: TypeBinder[EffectiveResourcePolicyPK] = new TypeBinder[EffectiveResourcePolicyPK] {
    def apply(rs: ResultSet, label: String): EffectiveResourcePolicyPK = nullSafe(rs.getLong(label), EffectiveResourcePolicyPK)
    def apply(rs: ResultSet, index: Int): EffectiveResourcePolicyPK = nullSafe(rs.getLong(index), EffectiveResourcePolicyPK)
  }

  implicit val policyNameTypeBinder: TypeBinder[AccessPolicyName] = new TypeBinder[AccessPolicyName] {
    def apply(rs: ResultSet, label: String): AccessPolicyName = nullSafe(rs.getString(label), AccessPolicyName)
    def apply(rs: ResultSet, index: Int): AccessPolicyName = nullSafe(rs.getString(index), AccessPolicyName)
  }

  implicit val resourceActionPatternPKTypeBinder: TypeBinder[ResourceActionPatternPK] = new TypeBinder[ResourceActionPatternPK] {
    def apply(rs: ResultSet, label: String): ResourceActionPatternPK = nullSafe(rs.getLong(label), ResourceActionPatternPK)
    def apply(rs: ResultSet, index: Int): ResourceActionPatternPK = nullSafe(rs.getLong(index), ResourceActionPatternPK)
  }

  implicit val resourceActionPatternNameTypeBinder: TypeBinder[ResourceActionPatternName] = new TypeBinder[ResourceActionPatternName] {
    def apply(rs: ResultSet, label: String): ResourceActionPatternName = nullSafe(rs.getString(label), ResourceActionPatternName)
    def apply(rs: ResultSet, index: Int): ResourceActionPatternName = nullSafe(rs.getString(index), ResourceActionPatternName)
  }

  implicit val resourceActionPKTypeBinder: TypeBinder[ResourceActionPK] = new TypeBinder[ResourceActionPK] {
    def apply(rs: ResultSet, label: String): ResourceActionPK = nullSafe(rs.getLong(label), ResourceActionPK)
    def apply(rs: ResultSet, index: Int): ResourceActionPK = nullSafe(rs.getLong(index), ResourceActionPK)
  }

  implicit val resourceActionNameTypeBinder: TypeBinder[ResourceAction] = new TypeBinder[ResourceAction] {
    def apply(rs: ResultSet, label: String): ResourceAction = nullSafe(rs.getString(label), ResourceAction)
    def apply(rs: ResultSet, index: Int): ResourceAction = nullSafe(rs.getString(index), ResourceAction)
  }

  implicit val resourceRolePKTypeBinder: TypeBinder[ResourceRolePK] = new TypeBinder[ResourceRolePK] {
    def apply(rs: ResultSet, label: String): ResourceRolePK = nullSafe(rs.getLong(label), ResourceRolePK)
    def apply(rs: ResultSet, index: Int): ResourceRolePK = nullSafe(rs.getLong(index), ResourceRolePK)
  }

  implicit val resourceRoleNameTypeBinder: TypeBinder[ResourceRoleName] = new TypeBinder[ResourceRoleName] {
    def apply(rs: ResultSet, label: String): ResourceRoleName = nullSafe(rs.getString(label), ResourceRoleName)
    def apply(rs: ResultSet, index: Int): ResourceRoleName = nullSafe(rs.getString(index), ResourceRoleName)
  }

  implicit val resourcePKTypeBinder: TypeBinder[ResourcePK] = new TypeBinder[ResourcePK] {
    def apply(rs: ResultSet, label: String): ResourcePK = nullSafe(rs.getLong(label), ResourcePK)
    def apply(rs: ResultSet, index: Int): ResourcePK = nullSafe(rs.getLong(index), ResourcePK)
  }

  implicit val resourceIdTypeBinder: TypeBinder[ResourceId] = new TypeBinder[ResourceId] {
    def apply(rs: ResultSet, label: String): ResourceId = nullSafe(rs.getString(label), ResourceId)
    def apply(rs: ResultSet, index: Int): ResourceId = nullSafe(rs.getString(index), ResourceId)
  }

  implicit val resourceTypePKTypeBinder: TypeBinder[ResourceTypePK] = new TypeBinder[ResourceTypePK] {
    def apply(rs: ResultSet, label: String): ResourceTypePK = nullSafe(rs.getLong(label), ResourceTypePK)
    def apply(rs: ResultSet, index: Int): ResourceTypePK = nullSafe(rs.getLong(index), ResourceTypePK)
  }

  implicit val resourceTypeNameTypeBinder: TypeBinder[ResourceTypeName] = new TypeBinder[ResourceTypeName] {
    def apply(rs: ResultSet, label: String): ResourceTypeName = nullSafe(rs.getString(label), ResourceTypeName)
    def apply(rs: ResultSet, index: Int): ResourceTypeName = nullSafe(rs.getString(index), ResourceTypeName)
  }

  implicit val workbenchGroupNameTypeBinder: TypeBinder[WorkbenchGroupName] = new TypeBinder[WorkbenchGroupName] {
    def apply(rs: ResultSet, label: String): WorkbenchGroupName = nullSafe(rs.getString(label), WorkbenchGroupName)
    def apply(rs: ResultSet, index: Int): WorkbenchGroupName = nullSafe(rs.getString(index), WorkbenchGroupName)
  }

  implicit val workbenchEmailTypeBinder: TypeBinder[WorkbenchEmail] = new TypeBinder[WorkbenchEmail] {
    def apply(rs: ResultSet, label: String): WorkbenchEmail = nullSafe(rs.getString(label), WorkbenchEmail)
    def apply(rs: ResultSet, index: Int): WorkbenchEmail = nullSafe(rs.getString(index), WorkbenchEmail)
  }

  implicit val googleProjectTypeBinder: TypeBinder[GoogleProject] = new TypeBinder[GoogleProject] {
    def apply(rs: ResultSet, label: String): GoogleProject = nullSafe(rs.getString(label), GoogleProject)
    def apply(rs: ResultSet, index: Int): GoogleProject = nullSafe(rs.getString(index), GoogleProject)
  }

  implicit val googleSubjectIdTypeBinder: TypeBinder[GoogleSubjectId] = new TypeBinder[GoogleSubjectId] {
    def apply(rs: ResultSet, label: String): GoogleSubjectId = nullSafe(rs.getString(label), GoogleSubjectId)
    def apply(rs: ResultSet, index: Int): GoogleSubjectId = nullSafe(rs.getString(index), GoogleSubjectId)
  }

  implicit val workbenchUserIdTypeBinder: TypeBinder[WorkbenchUserId] = new TypeBinder[WorkbenchUserId] {
    def apply(rs: ResultSet, label: String): WorkbenchUserId = nullSafe(rs.getString(label), WorkbenchUserId)
    def apply(rs: ResultSet, index: Int): WorkbenchUserId = nullSafe(rs.getString(index), WorkbenchUserId)
  }

  implicit val serviceAccountDisplayNameTypeBinder: TypeBinder[ServiceAccountDisplayName] = new TypeBinder[ServiceAccountDisplayName] {
    def apply(rs: ResultSet, label: String): ServiceAccountDisplayName = nullSafe(rs.getString(label), ServiceAccountDisplayName)
    def apply(rs: ResultSet, index: Int): ServiceAccountDisplayName = nullSafe(rs.getString(index), ServiceAccountDisplayName)
  }

  implicit val serviceAccountSubjectIdTypeBinder: TypeBinder[ServiceAccountSubjectId] = new TypeBinder[ServiceAccountSubjectId] {
    def apply(rs: ResultSet, label: String): ServiceAccountSubjectId = nullSafe(rs.getString(label), ServiceAccountSubjectId)
    def apply(rs: ResultSet, index: Int): ServiceAccountSubjectId = nullSafe(rs.getString(index), ServiceAccountSubjectId)
  }

  implicit val flatGroupMemberPKTypeBinder: TypeBinder[GroupMemberFlatPK] = new TypeBinder[GroupMemberFlatPK] {
    def apply(rs: ResultSet, label: String): GroupMemberFlatPK = nullSafe(rs.getLong(label), GroupMemberFlatPK)
    def apply(rs: ResultSet, index: Int): GroupMemberFlatPK = nullSafe(rs.getLong(index), GroupMemberFlatPK)
  }

  implicit val flatGroupMembershipPathPKTypeBinder: TypeBinder[GroupMembershipPath] = new TypeBinder[GroupMembershipPath] {
    def apply(rs: ResultSet, label: String): GroupMembershipPath =
      GroupMembershipPath(rs.getArray(label).getArray.asInstanceOf[Array[java.lang.Long]].map(_.longValue()).toList.map(GroupPK))
    def apply(rs: ResultSet, index: Int): GroupMembershipPath =
      GroupMembershipPath(rs.getArray(index).getArray.asInstanceOf[Array[java.lang.Long]].map(_.longValue()).toList.map(GroupPK))
  }

  implicit val tenantIdTypeBinder: TypeBinder[TenantId] = new TypeBinder[TenantId] {
    def apply(rs: ResultSet, label: String): TenantId = nullSafe(rs.getString(label), TenantId)
    def apply(rs: ResultSet, index: Int): TenantId = nullSafe(rs.getString(index), TenantId)
  }

  implicit val subscriptionIdTypeBinder: TypeBinder[SubscriptionId] = new TypeBinder[SubscriptionId] {
    def apply(rs: ResultSet, label: String): SubscriptionId = nullSafe(rs.getString(label), SubscriptionId)
    def apply(rs: ResultSet, index: Int): SubscriptionId = nullSafe(rs.getString(index), SubscriptionId)
  }

  implicit val managedResourceGroupNameTypeBinder: TypeBinder[ManagedResourceGroupName] = new TypeBinder[ManagedResourceGroupName] {
    def apply(rs: ResultSet, label: String): ManagedResourceGroupName = nullSafe(rs.getString(label), ManagedResourceGroupName)
    def apply(rs: ResultSet, index: Int): ManagedResourceGroupName = nullSafe(rs.getString(index), ManagedResourceGroupName)
  }

  implicit val managedIdentityObjectIdTypeBinder: TypeBinder[ManagedIdentityObjectId] = new TypeBinder[ManagedIdentityObjectId] {
    def apply(rs: ResultSet, label: String): ManagedIdentityObjectId = nullSafe(rs.getString(label), ManagedIdentityObjectId)
    def apply(rs: ResultSet, index: Int): ManagedIdentityObjectId = nullSafe(rs.getString(index), ManagedIdentityObjectId)
  }

  implicit val managedIdentityDisplayNameTypeBinder: TypeBinder[ManagedIdentityDisplayName] = new TypeBinder[ManagedIdentityDisplayName] {
    def apply(rs: ResultSet, label: String): ManagedIdentityDisplayName = nullSafe(rs.getString(label), ManagedIdentityDisplayName)
    def apply(rs: ResultSet, index: Int): ManagedIdentityDisplayName = nullSafe(rs.getString(index), ManagedIdentityDisplayName)
  }

  implicit val ManagedResourceGroupPKTypeBinder: TypeBinder[ManagedResourceGroupPK] = new TypeBinder[ManagedResourceGroupPK] {
    def apply(rs: ResultSet, label: String): ManagedResourceGroupPK = nullSafe(rs.getLong(label), ManagedResourceGroupPK)
    def apply(rs: ResultSet, index: Int): ManagedResourceGroupPK = nullSafe(rs.getLong(index), ManagedResourceGroupPK)
  }

  implicit val BillingProfileIdTypeBinder: TypeBinder[BillingProfileId] = new TypeBinder[BillingProfileId] {
    def apply(rs: ResultSet, label: String): BillingProfileId = nullSafe(rs.getString(label), BillingProfileId)
    def apply(rs: ResultSet, index: Int): BillingProfileId = nullSafe(rs.getString(index), BillingProfileId)
  }

  implicit val lastQuotaErrorPKTypeBinder: TypeBinder[LastQuotaErrorPK] = new TypeBinder[LastQuotaErrorPK] {
    def apply(rs: ResultSet, label: String): LastQuotaErrorPK = nullSafe(rs.getLong(label), LastQuotaErrorPK)
    def apply(rs: ResultSet, index: Int): LastQuotaErrorPK = nullSafe(rs.getLong(index), LastQuotaErrorPK)
  }

  private def nullSafe[V, T >: Null](value: V, constructor: V => T): T = Option(value).map(constructor).orNull
}
