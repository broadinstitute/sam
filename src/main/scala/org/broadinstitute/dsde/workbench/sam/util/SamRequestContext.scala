package org.broadinstitute.dsde.workbench.sam.util

import io.opencensus.trace.Span

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
case class SamRequestContext (parentSpan: Option[Span])
