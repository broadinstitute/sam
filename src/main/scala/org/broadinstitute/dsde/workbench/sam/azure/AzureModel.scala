package org.broadinstitute.dsde.workbench.sam.azure

import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.model.{ResourceAction, ResourceId}
import spray.json.DefaultJsonProtocol._

object AzureJsonSupport {
  implicit val tenantIdFormat = ValueObjectFormat(TenantId.apply)

  implicit val subscriptionIdFormat = ValueObjectFormat(SubscriptionId.apply)

  implicit val managedResourceGroupNameFormat = ValueObjectFormat(ManagedResourceGroupName.apply)

  implicit val getPetManagedIdentityRequestFormat = jsonFormat3(GetOrCreatePetManagedIdentityRequest.apply)

  implicit val managedIdentityObjectIdFormat = ValueObjectFormat(ManagedIdentityObjectId.apply)

  implicit val managedIdentityDisplayNameFormat = ValueObjectFormat(ManagedIdentityDisplayName.apply)

  implicit val petManagedIdentityIdFormat = jsonFormat4(PetManagedIdentityId.apply)

  implicit val petManagedIdentityFormat = jsonFormat3(PetManagedIdentity.apply)

  implicit val managedResourceGroupCoordinatesFormat = jsonFormat3(ManagedResourceGroupCoordinates.apply)
}

final case class TenantId(value: String) extends ValueObject
final case class SubscriptionId(value: String) extends ValueObject
final case class ManagedResourceGroupName(value: String) extends ValueObject
final case class ManagedIdentityObjectId(value: String) extends ValueObject
final case class ManagedIdentityDisplayName(value: String) extends ValueObject
final case class BillingProfileId(value: String) extends ValueObject
final case class ManagedResourceGroupCoordinates(
    tenantId: TenantId,
    subscriptionId: SubscriptionId,
    managedResourceGroupName: ManagedResourceGroupName
)
final case class ManagedResourceGroup(
    managedResourceGroupCoordinates: ManagedResourceGroupCoordinates,
    billingProfileId: BillingProfileId
)

final case class GetOrCreatePetManagedIdentityRequest(tenantId: TenantId, subscriptionId: SubscriptionId, managedResourceGroupName: ManagedResourceGroupName) {
  def toManagedResourceGroupCoordinates = ManagedResourceGroupCoordinates(tenantId, subscriptionId, managedResourceGroupName)
}

final case class PetManagedIdentityId(
    user: WorkbenchUserId,
    tenantId: TenantId,
    subscriptionId: SubscriptionId,
    managedResourceGroupName: ManagedResourceGroupName
)

final case class PetManagedIdentity(id: PetManagedIdentityId, objectId: ManagedIdentityObjectId, displayName: ManagedIdentityDisplayName)

object AzureExtensions {
  val resourceId = ResourceId("azure")
  val getPetManagedIdentityAction = ResourceAction("get_pet_managed_identity")
}
