package org.broadinstitute.dsde.workbench.sam.model.api

import monocle.macros.Lenses
import org.broadinstitute.dsde.workbench.model.{ValueObject, ValueObjectFormat, WorkbenchEmail, WorkbenchGroupName}
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedResourceId, ResourceId, ResourceRoleName}
import org.broadinstitute.dsde.workbench.sam.service.ManagedGroupService.MangedGroupRoleName
import spray.json.DefaultJsonProtocol

@Lenses final case class ManagedGroupAndRole(groupName: WorkbenchGroupName, role: MangedGroupRoleName)
@Lenses final case class ManagedGroupMembershipEntry(groupName: ResourceId, role: ResourceRoleName, groupEmail: WorkbenchEmail)
@Lenses final case class ManagedGroupAccessInstructions(value: String) extends ValueObject
@Lenses final case class ManagedGroupSupportSummary(
    groupName: WorkbenchGroupName,
    email: WorkbenchEmail,
    authDomainFor: Set[FullyQualifiedResourceId],
    parentGroups: Set[WorkbenchGroupName]
)

object ManagedGroupModelJsonSupport {
  import DefaultJsonProtocol._
  import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
  import SamJsonSupport.{FullyQualifiedResourceIdFormat, ResourceIdFormat, ResourceRoleNameFormat}

  implicit val ManagedGroupMembershipEntryFormat = jsonFormat3(ManagedGroupMembershipEntry.apply)

  implicit val ManagedGroupAccessInstructionsFormat = ValueObjectFormat(ManagedGroupAccessInstructions.apply)

  implicit val ManagedGroupSupportSummaryFormat = jsonFormat4(ManagedGroupSupportSummary.apply)
}
