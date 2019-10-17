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
    traceIOSpan(startSpanWithParent(name, parentSpan), failureStatus)(f)

  def traceIO[T](
                  name: String,
                  failureStatus: Throwable => Status = (_: Throwable) => Status.UNKNOWN
                )(f: Span => IO[T]) : IO[T] = {

    traceIOSpan(startSpan(name), failureStatus)(f)
  }


  private def traceIOSpan[T](span: Span, failureStatus: Throwable => Status) (f: Span => IO[T]): IO[T] = {
    val acquire = IO.pure(1)
    def release(s: Span) = IO(endSpan(s, Status.OK))

    val resource = Resource.make(acquire)(_ => release(span))

    resource.use(_ => f(span) )
  }

}