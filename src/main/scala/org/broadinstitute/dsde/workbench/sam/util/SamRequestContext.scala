package org.broadinstitute.dsde.workbench.sam.util

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context
import org.broadinstitute.dsde.workbench.sam.audit.AuditInfo
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser

import java.net.InetAddress

/** Contains any additional data for the request.
  *
  * SamRequestContext was created with the goal of pre-empting a repeat of the crazy plumbing that was required to add the trace. In the future, instead of
  * needing to add a new param to each API and underlying function, we can just add to this class instead.
  *
  * @param otelContext
  *   the parent span for tracing spans. To create a new parent span, create a new context with the new parent span.
  */
case class SamRequestContext(
    otelContext: Option[Context] = None,
    clientIp: Option[InetAddress] = None,
    samUser: Option[SamUser] = None,
    openTelemetry: OpenTelemetry = OpenTelemetry.noop
) {
  def createAuditInfo: AuditInfo = AuditInfo(samUser.map(_.id), clientIp)
}
