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
                             samRequestContext: SamRequestContext,
                             failureStatus: Throwable => Status = (_: Throwable) => Status.UNKNOWN
                          )(f: Span => IO[T]): IO[T] = { // todo: change signature of this to be SamRequestContext ; probably requires a new traceIOContext()
    if (samRequestContext == null || samRequestContext.parentSpan == null) { // todo: once all nulls are removed, this won't be necessary.
      for {
        result <- f(null).attempt
      } yield result.toTry.get
    }
    else {
      traceIOSpan(IO(startSpanWithParent(name, samRequestContext.parentSpan)), failureStatus)(f)
    }
  }

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
  def completeWithTrace(request: SamRequestContext => ToResponseMarshallable): Route =
    traceRequest {span =>
      val samRequestContext = new SamRequestContext(span)
      complete {
        request(samRequestContext)
      }
    }
}
