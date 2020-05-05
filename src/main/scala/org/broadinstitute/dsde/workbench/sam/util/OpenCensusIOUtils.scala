package org.broadinstitute.dsde.workbench.sam.util

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import io.opencensus.trace.{Span, Status}
import io.opencensus.scala.Tracing._
import io.opencensus.scala.akka.http.TracingDirective.traceRequest
import cats.effect.IO

object OpenCensusIOUtils {

  def traceIOWithContext[T](
                             name: String,
                             samRequestContext: SamRequestContext,
                             failureStatus: Throwable => Status = (_: Throwable) => Status.UNKNOWN
                          )(f: SamRequestContext => IO[T]): IO[T] = {
    if (samRequestContext.parentSpan == null) { // ignore calls from unit tests and calls on boot; an alternative here would be to have a MockOpenCensusIOUtils to unit tests
      f(samRequestContext)
    }
    else {
      traceIOSpan(name, IO(startSpanWithParent(name, samRequestContext.parentSpan)), samRequestContext, failureStatus)(f)
    }
  }

  // creates a root span
  def traceIO[T](
                  name: String,
                  samRequestContext: SamRequestContext,
                  failureStatus: Throwable => Status = (_: Throwable) => Status.UNKNOWN
                )(f: SamRequestContext => IO[T]) : IO[T] = {

    traceIOSpan(name, IO(startSpan(name)), samRequestContext, failureStatus)(f)
  }


  private def traceIOSpan[T](name: String, spanIO: IO[Span], samRequestContext: SamRequestContext, failureStatus: Throwable => Status) (f: SamRequestContext => IO[T]): IO[T] = {
    for {
      span <- spanIO
      result <- f(samRequestContext.copy(parentSpan = span)).attempt
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
