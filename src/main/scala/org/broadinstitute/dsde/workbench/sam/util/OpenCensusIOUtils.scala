package org.broadinstitute.dsde.workbench.sam.util

import io.opencensus.trace.{Span, Status}
import io.opencensus.scala.Tracing._

import cats.effect.{IO, Resource}


object OpenCensusIOUtils {

  def traceIOWithParent[T](
                            name: String,
                            parentSpan: Span,
                            failureStatus: Throwable => Status = (_: Throwable) => Status.UNKNOWN
                          )(f: Span => IO[T]): IO[T] =
    traceIOSpan(IO(startSpanWithParent(name, parentSpan)), failureStatus)(f)

  def traceIO[T](
                  name: String,
                  failureStatus: Throwable => Status = (_: Throwable) => Status.UNKNOWN
                )(f: Span => IO[T]) : IO[T] = {

    traceIOSpan(IO(startSpan(name)), failureStatus)(f)
  }


  private def traceIOSpan[T](spanIO: IO[Span], failureStatus: Throwable => Status) (f: Span => IO[T]): IO[T] = {
    def release(s: Span) = IO(endSpan(s, Status.OK))

    val resource = Resource.make(spanIO)(release)

    resource.use(f)
  }

}