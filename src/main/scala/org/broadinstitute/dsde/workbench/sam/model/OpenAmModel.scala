package org.broadinstitute.dsde.workbench.sam.model

import spray.json.DefaultJsonProtocol

/**
  * Created by dvoet on 6/5/17.
  */
object OpenAmJsonSupport extends DefaultJsonProtocol {

  implicit val OpenAmPolicySubjectFormat = jsonFormat2(OpenAmPolicySubject)

  implicit val OpenAmPolicyFormat = jsonFormat8(OpenAmPolicy)

  implicit val OpenAmResourceTypeFormat = jsonFormat4(OpenAmResourceType)

  implicit val OpenAmResourceTypePayloadFormat = jsonFormat3(OpenAmResourceTypePayload)

  implicit val OpenAmResourceTypeListFormat = jsonFormat1(OpenAmResourceTypeList)

  implicit val OpenAmPolicySetFormat = jsonFormat7(OpenAmPolicySet)

  implicit val AuthenticateResponseFormat = jsonFormat1(AuthenticateResponse)
}

case class OpenAmPolicy(
                 name: String,
                 active: Boolean,
                 description: String,
                 applicationName: String,
                 actionValues: Map[String, Boolean],
                 resources: Seq[String],
                 subject: OpenAmPolicySubject,
                 resourceTypeUuid: String
                 )

/**
  *
  * @param subjectValues
  * @param `type` legal values for type are AuthenticatedUsers, Identity, JwtClaim and None
  *               but we will almost always use Identity
  */
case class OpenAmPolicySubject(subjectValues: Seq[String], `type`: String = "Identity")

case class OpenAmResourceTypePayload(name: String, actions: Map[String, Boolean], patterns: Set[String])
case class OpenAmResourceType(name: String, actions: Map[String, Boolean], patterns: Set[String], uuid: String)

case class OpenAmResourceTypeList(result: Set[OpenAmResourceType])

case class OpenAmPolicySet(name: String, description: String, conditions: Set[String], subjects: Set[String], applicationType: String, entitlementCombiner: String, resourceTypeUuids: Set[String])

case class AuthenticateResponse(tokenId: String)
