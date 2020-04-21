package org.broadinstitute.dsde.workbench.sam.util

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import io.opencensus.trace.{Span, Status}
import io.opencensus.scala.Tracing._
import io.opencensus.scala.akka.http.TracingDirective.traceRequest
import cats.effect.IO

object OpenCensusIOUtils {

  // todo: this is unused
  def traceIOWithParent[T](
                            name: String,
                            parentSpan: Span,
                            failureStatus: Throwable => Status = (_: Throwable) => Status.UNKNOWN
                          )(f: Span => IO[T]): IO[T] =
    traceIOSpan(IO(startSpanWithParent(name, parentSpan)), failureStatus)(f)

  def traceIOWithContext[T](
                            name: String,
                            traceContext: TraceContext,
                            failureStatus: Throwable => Status = (_: Throwable) => Status.UNKNOWN
                          )(f: Span => IO[T]): IO[T] =
    traceIOSpan(IO(startSpanWithParent(name, traceContext.parentSpan)), failureStatus)(f)

  // todo: this is unused
  // creates a root span
  def traceIO[T](
                  name: String,
                  failureStatus: Throwable => Status = (_: Throwable) => Status.UNKNOWN
                )(f: Span => IO[T]) : IO[T] = {

    traceIOSpan(IO(startSpan(name)), failureStatus)(f)
  }


  private def traceIOSpan[T](spanIO: IO[Span], failureStatus: Throwable => Status) (f: Span => IO[T]): IO[T] = {
    for {
      span <- spanIO
      result <- f(span).attempt
      _ <- IO(endSpan(span, Status.OK))
    } yield result.toTry.get
  }

  // Makes a complete() akka-http call, with tracing added, at the rate specified in config/sam.conf (a generated conf file)
  def completeWithTrace(request: TraceContext => ToResponseMarshallable): Route =
    traceRequest {span =>
      val traceContext = new TraceContext(span)
      complete {
        request(traceContext)
      }
    }
}
