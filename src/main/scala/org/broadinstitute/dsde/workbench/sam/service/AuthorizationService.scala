package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.openTelemetry.OpenTelemetryMetrics
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO

import scala.concurrent.ExecutionContext

class AuthorizationService(val directoryDAO: DirectoryDAO, val cloudExtensions: CloudExtensions, blockedEmailDomains: Seq[String], tosService: TosService)(
    implicit
    val executionContext: ExecutionContext,
    val openTelemetry: OpenTelemetryMetrics[IO]
) extends LazyLogging {}
