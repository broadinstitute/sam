package org.broadinstitute.dsde.workbench.sam.util

import cats.effect.IO
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.instrumenter.{Instrumenter, SpanKindExtractor}

object OpenTelemetryIOUtils {

  def traceIOWithContext[T](
      name: String,
      samRequestContext: SamRequestContext
  )(f: SamRequestContext => IO[T]): IO[T] =
    samRequestContext.otelContext match {
      case Some(otelContext) =>
        traceIOSpan(name, otelContext, samRequestContext)(f)
      case None => // ignore calls from unit tests and calls on boot
        f(samRequestContext)
    }

  // creates a root span
  def traceIO[T](
      name: String,
      samRequestContext: SamRequestContext
  )(f: SamRequestContext => IO[T]): IO[T] =
    traceIOSpan(name, Context.root(), samRequestContext)(f)

  private def traceIOSpan[T](name: String, otelContext: Context, samRequestContext: SamRequestContext)(
      f: SamRequestContext => IO[T]
  ): IO[T] = {
    val instrumenter: Instrumenter[Object, Object] =
      Instrumenter
        .builder[Object, Object](samRequestContext.openTelemetry, "OpenTelemetryIOUtils", _ => name)
        .buildInstrumenter(SpanKindExtractor.alwaysInternal())

    if (instrumenter.shouldStart(otelContext, name)) {
      for {
        childContext <- IO(instrumenter.start(otelContext, name))
        result <- f(samRequestContext.copy(otelContext = Option(childContext))).attempt
        _ = instrumenter.end(childContext, name, name, result.toTry.failed.toOption.orNull)
      } yield result.toTry.get
    } else {
      f(samRequestContext)
    }
  }
}
