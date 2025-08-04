package org.broadinstitute.dsde.workbench.sam.metrics

import akka.http.scaladsl.server.Rejection

case class UnregisteredUserApiEvent(
    override val event: String,
    override val request: RequestEventDetails,
    override val response: Option[ResponseEventDetails] = None,
    override val rejections: Seq[Rejection] = Seq()
) extends ApiEvent
