package org.broadinstitute.dsde.workbench.sam.util

import io.opencensus.trace.Span
import org.broadinstitute.dsde.workbench.sam.audit.AuditInfo
import org.broadinstitute.dsde.workbench.sam.model.SamUser

import java.net.InetAddress
import java.time.Instant

/**
  * Contains any additional data for the request.
  *
  * SamRequestContext was created with the goal of pre-empting a repeat of the crazy plumbing that was required to add
  * the trace. In the future, instead of needing to add a new param to each API and underlying function, we can just add
  * to this class instead.
  *
  * @param parentSpan the parent span for tracing spans. To create a new parent span, create a new context with the new
  *                   parent span.
  *
 */
case class SamRequestContext(parentSpan: Option[Span] = None,
                             clientIp: Option[InetAddress] = None,
                             samUser: Option[SamUser] = None) {
  def createAuditInfo: AuditInfo = AuditInfo(samUser.map(_.id), Instant.now(), clientIp)
}
