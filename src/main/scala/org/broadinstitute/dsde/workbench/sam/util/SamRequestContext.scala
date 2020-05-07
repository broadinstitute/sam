package org.broadinstitute.dsde.workbench.sam.util

import io.opencensus.trace.Span

/*
SamRequestContext was created with the goal of pre-empting a repeat of the crazy plumbing that was required to add
the trace. In the future, instead of needing to add a new param to each API and underlying function, we can just add
to this class instead.
 */
case class SamRequestContext (parentSpan: Option[Span])
