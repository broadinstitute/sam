package org.broadinstitute.dsde.workbench.sam.audit

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import net.logstash.logback.argument.StructuredArguments
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import spray.json.{JsonWriter, enrichAny}
import SamAuditModelJsonSupport.AuditInfoFormat

object AuditLogger extends LazyLogging {
  def logAuditEventIO[T <: AuditEvent: JsonWriter](samRequestContext: SamRequestContext, auditEvents: T*): IO[Unit] =
    IO(auditEvents.foreach(logAuditEvent(_, samRequestContext)))

  def logAuditEvent[T <: AuditEvent: JsonWriter](auditEvent: T, samRequestContext: SamRequestContext): Unit =
    logger.whenInfoEnabled {
      logger.info(
        auditEvent.eventType.toString,
        StructuredArguments.raw("eventDetails", auditEvent.toJson.compactPrint),
        StructuredArguments.raw("auditInfo", samRequestContext.createAuditInfo.toJson.compactPrint)
      )
    }
}
