package org.broadinstitute.dsde.workbench.sam.audit

import org.broadinstitute.dsde.workbench.model.{WorkbenchException, WorkbenchGroupName, WorkbenchSubject, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedPolicyId, FullyQualifiedResourceId, ResourceAction, ResourceRoleName, ResourceTypeName}
import spray.json.{DefaultJsonProtocol, DeserializationException, JsObject, JsString, JsValue, RootJsonFormat, SerializationException}

import java.net.InetAddress

sealed trait AuditEventType
sealed trait AuditEvent {
  val eventType: AuditEventType
}

final case class AuditInfo(userId: Option[WorkbenchUserId], clientIp: Option[InetAddress])

final case class ResourceChange(parent: FullyQualifiedResourceId)

sealed trait ResourceEventType extends AuditEventType
case object ResourceCreated extends ResourceEventType
case object ResourceParentUpdated extends ResourceEventType
case object ResourceParentRemoved extends ResourceEventType
case object ResourceDeleted extends ResourceEventType

final case class ResourceEvent(eventType: ResourceEventType,
                               resource: FullyQualifiedResourceId,
                               changeDetails: Option[ResourceChange] = None) extends AuditEvent

case class AccessChange(member: WorkbenchSubject,
                        roles: Option[Iterable[ResourceRoleName]],
                        actions: Option[Iterable[ResourceAction]],
                        descendantRoles: Option[Map[ResourceTypeName, Iterable[ResourceRoleName]]],
                        descendantActions: Option[Map[ResourceTypeName, Iterable[ResourceAction]]])

sealed trait AccessChangeEventType extends AuditEventType
case object AccessAdded extends AccessChangeEventType
case object AccessRemoved extends AccessChangeEventType

final case class AccessChangeEvent(eventType: AccessChangeEventType,
                                   resource: FullyQualifiedResourceId,
                                   changeDetails: Set[AccessChange]) extends AuditEvent

object SamAuditModelJsonSupport {
  import DefaultJsonProtocol._
  import org.broadinstitute.dsde.workbench.model.WorkbenchIdentityJsonSupport._
  import org.broadinstitute.dsde.workbench.sam.model.SamJsonSupport._

  implicit object InetAddressFormat extends RootJsonFormat[InetAddress] {
    def write(ip: InetAddress) = JsString(ip.getHostAddress)

    def read(value: JsValue): InetAddress = value match {
      case JsString(str) => InetAddress.getByAddress(str.getBytes)
      case _             => throw new WorkbenchException(s"Unable to unmarshal InetAddress from $value")
    }
  }

  implicit val AuditInfoFormat = jsonFormat2(AuditInfo)

  implicit val ResourceChangeFormat = jsonFormat1(ResourceChange)
  implicit val ResourceEventTypeFormat = new RootJsonFormat[ResourceEventType] {
    def read(obj: JsValue): ResourceEventType = obj match {
      case JsString("ResourceCreated") => ResourceCreated
      case JsString("ResourceParentUpdated") => ResourceParentUpdated
      case JsString("ResourceParentRemoved") => ResourceParentRemoved
      case JsString("ResourceDeleted") => ResourceDeleted
      case _                           => throw new DeserializationException(s"could not deserialize ResourceEventType: $obj")
    }

    def write(obj: ResourceEventType): JsValue = JsString(obj.toString)
  }

  implicit val ResourceEventFormat = jsonFormat3(ResourceEvent)

  implicit val WorkbenchSubjectFormat = new RootJsonFormat[WorkbenchSubject] {
    val MEMBER_TYPE_FIELD = "memberType"
    val USER_TYPE = "user"
    val GROUP_TYPE = "group"
    val POLICY_TYPE = "policy"
    val USER_ID_FIELD = "userId"
    val GROUP_NAME_FIELD = "groupName"
    val POLICY_ID_FIELD = "policyId"

    override def read(json: JsValue): WorkbenchSubject = {
      json match {
        case JsObject(fields) =>
          val maybeSubject: Option[WorkbenchSubject] = fields.get(MEMBER_TYPE_FIELD) match {
            case Some(JsString(USER_TYPE)) => fields.get(USER_ID_FIELD).map(WorkbenchUserIdFormat.read)
            case Some(JsString(GROUP_TYPE)) => fields.get(GROUP_NAME_FIELD).map(WorkbenchGroupNameFormat.read)
            case Some(JsString(POLICY_TYPE)) => fields.get(POLICY_ID_FIELD).map(FullyQualifiedPolicyIdFormat.read)
            case _ => None
          }
          maybeSubject.getOrElse(throw new DeserializationException(s"could not deserialize WorkbenchSubject: $json"))

        case _ =>
          throw new DeserializationException(s"could not deserialize WorkbenchSubject: $json")
      }
    }

    override def write(obj: WorkbenchSubject): JsValue = {
      obj match {
        case user: WorkbenchUserId =>
          JsObject(Map(MEMBER_TYPE_FIELD -> JsString(USER_TYPE), USER_ID_FIELD -> WorkbenchUserIdFormat.write(user)))
        case groupName: WorkbenchGroupName =>
          JsObject(Map(MEMBER_TYPE_FIELD -> JsString(GROUP_TYPE), GROUP_NAME_FIELD -> WorkbenchGroupNameFormat.write(groupName)))
        case policyId: FullyQualifiedPolicyId =>
          JsObject(Map(MEMBER_TYPE_FIELD -> JsString(POLICY_TYPE), POLICY_ID_FIELD -> FullyQualifiedPolicyIdFormat.write(policyId)))

        case _ => throw new SerializationException(s"could not serialize WorkbenchSubject: $obj")
      }
    }
  }

  implicit val AccessChangeFormat = jsonFormat5(AccessChange)
  implicit val AccessChangeEventTypeFormat = new RootJsonFormat[AccessChangeEventType] {
    def read(obj: JsValue): AccessChangeEventType = obj match {
      case JsString("AccessAdded") => AccessAdded
      case JsString("AccessRemoved") => AccessRemoved
      case _                         => throw new DeserializationException(s"could not deserialize AccessChangeEventType: $obj")
    }

    def write(obj: AccessChangeEventType): JsValue = JsString(obj.toString)
  }

  implicit val PolicyEventFormat = jsonFormat3(AccessChangeEvent)
}