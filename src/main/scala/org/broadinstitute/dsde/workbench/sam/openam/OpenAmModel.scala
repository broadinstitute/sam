package org.broadinstitute.dsde.workbench.sam.openam

import org.broadinstitute.dsde.workbench.sam.model.{OpenAmPolicySet, OpenAmResourceType}

/**
  * Created by dvoet on 6/5/17.
  */
object OpenAmJsonSupport {
  import spray.json.DefaultJsonProtocol._

  implicit val OpenAmPolicySubjectFormat = jsonFormat2(OpenAmPolicySubject)
  implicit val OpenAmPolicyFormat = jsonFormat8(OpenAmPolicy)

  implicit val openAmResourceTypeFormat = jsonFormat3(OpenAmResourceType)
  implicit val openAmPolicySetFormat = jsonFormat6(OpenAmPolicySet)

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

case class AuthenticateResponse(tokenId: String)