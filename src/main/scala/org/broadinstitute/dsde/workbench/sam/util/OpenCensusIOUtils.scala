package org.broadinstitute.dsde.workbench.sam.util

import io.opencensus.trace.{Span, Status}
import io.opencensus.scala.Tracing._
import cats.effect.IO

object OpenCensusIOUtils {

  def traceIOWithContext[T](
      name: String,
      samRequestContext: SamRequestContext,
      failureStatus: Throwable => Status = (_: Throwable) => Status.UNKNOWN
  )(f: SamRequestContext => IO[T]): IO[T] =
    samRequestContext.parentSpan match {
      case Some(parentSpan) =>
        traceIOSpan(name, IO(startSpanWithParent(name, parentSpan)), samRequestContext, failureStatus)(f)
      case None => // ignore calls from unit tests and calls on boot
        f(samRequestContext)
    }

  // creates a root span
  def traceIO[T](
      name: String,
      samRequestContext: SamRequestContext,
      failureStatus: Throwable => Status = (_: Throwable) => Status.UNKNOWN
  )(f: SamRequestContext => IO[T]): IO[T] =
    traceIOSpan(name, IO(startSpan(name)), samRequestContext, failureStatus)(f)

  private def traceIOSpan[T](name: String, spanIO: IO[Span], samRequestContext: SamRequestContext, failureStatus: Throwable => Status)(
      f: SamRequestContext => IO[T]
  ): IO[T] =
    for {
      span <- spanIO
      result <- f(samRequestContext.copy(parentSpan = Option(span), None)).attempt
      _ <- IO(endSpan(span, Status.OK))
    } yield result.toTry.get
}
