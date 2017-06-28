package org.broadinstitute.dsde.workbench.sam.model

import org.broadinstitute.dsde.workbench.sam.WorkbenchException
import spray.json.DefaultJsonProtocol

/**
  * Created by dvoet on 5/26/17.
  */

object SamJsonSupport extends DefaultJsonProtocol {
  implicit val ResourceActionFormat = ValueObjectFormat(ResourceAction)

  implicit val ResourceRoleNameFormat = ValueObjectFormat(ResourceRoleName)

  implicit val ResourceRoleFormat = jsonFormat2(ResourceRole)

  implicit val ResourceTypeNameFormat = ValueObjectFormat(ResourceTypeName)

  implicit val ResourceTypeFormat = jsonFormat4(ResourceType)
}

sealed trait SamSubject
case class SamUserId(value: String) extends SamSubject with ValueObject
case class SamUserEmail(value: String)
case class SamUser(id: SamUserId, firstName: String, lastName: String, email: Option[SamUserEmail])

case class SamGroupName(value: String) extends SamSubject with ValueObject
case class SamGroup(name: SamGroupName, members: Set[SamSubject])

case class ResourceAction(value: String) extends ValueObject
case class ResourceRoleName(value: String) extends ValueObject
case class ResourceRole(roleName: ResourceRoleName, actions: Set[ResourceAction])

case class ResourceTypeName(value: String) extends ValueObject

case class ResourceType(name: ResourceTypeName, actions: Set[ResourceAction], roles: Set[ResourceRole], ownerRoleName: ResourceRoleName)

case class ResourceName(value: String) extends ValueObject
case class AccessPolicyId(value: String) extends ValueObject
case class AccessPolicy(id: AccessPolicyId, actions: Set[ResourceAction], resourceType: ResourceTypeName, resource: ResourceName, subject: SamSubject, role: Option[ResourceRoleName])
