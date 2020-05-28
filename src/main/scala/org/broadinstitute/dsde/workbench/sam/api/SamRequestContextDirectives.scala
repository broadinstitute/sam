package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, ExceptionHandler}
import io.opencensus.scala.Tracing
import io.opencensus.scala.Tracing.startSpanWithParent
import io.opencensus.scala.akka.http.TracingDirective.traceRequest
import io.opencensus.scala.akka.http.trace.HttpExtractors._
import io.opencensus.scala.akka.http.utils.ExecuteAfterResponse
import io.opencensus.scala.http.{HttpAttributes, StatusTranslator}
import io.opencensus.trace.{Span, Status}
import org.broadinstitute.dsde.workbench.sam.service.UserService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.util.control.NonFatal

/**
  * Created by ajang on 2020-05-28
  */
trait SamRequestContextDirectives {
  val userService: UserService

  /**
    * Provides a new SamRequestContext with a root tracing span.
    */
  def withSamRequestContext: Directive1[SamRequestContext] =
    traceRequest.map { span => SamRequestContext(Option(span)) }

  /**
    * Provides a new SamRequestContext with a tracing span that is a child of the existing SamRequestContext's
    * `parentSpan`. If a parentSpan does not exist, this will NOT create a new one.
    *
    * @param spanName name of the new parentSpan in the new SamRequestContext. This span is a child of the existing
    *                 parentSpan.
    * @param samRequestContext the existing samRequestContext.
    */
  def withNewTraceSpan(spanName: String, samRequestContext: SamRequestContext): Directive1[SamRequestContext] =
    samRequestContext.parentSpan match {
      case Some (parentSpan) =>
        val newSpan = startSpanWithParent(spanName, parentSpan)
        val newSamRequestContext = samRequestContext.copy(parentSpan = Option(newSpan))
        recordSuccess(newSpan) & recordException(newSpan) & provide(newSamRequestContext)
      case None => provide(SamRequestContext(None))
    }

  // The private methods and objects below are copied wholesale from io/opencensus/scala/akka/http/TracingDirective
  // which seems to be the lesser evil compared to accessing the package's private methods.
  private def tracing: Tracing = Tracing
  private def recordSuccess(span: Span) =
    mapResponse(EndSpanResponse.forServer(tracing, _, span))
  private def recordException(span: Span) =
    handleExceptions(ExceptionHandler {
      case NonFatal(ex) =>
        tracing.endSpan(span, Status.INTERNAL)
        throw ex
    })
  private object EndSpanResponse {

    def forServer(
                   tracing: Tracing,
                   response: HttpResponse,
                   span: Span
                 ): HttpResponse =
      end(tracing, response, span, "response sent")

    def forClient(
                   tracing: Tracing,
                   response: HttpResponse,
                   span: Span
                 ): HttpResponse =
      end(tracing, response, span, "response received")

    private def end(
                     tracing: Tracing,
                     response: HttpResponse,
                     span: Span,
                     responseAnnotation: String
                   ): HttpResponse = {
      HttpAttributes.setAttributesForResponse(span, response)
      span.addAnnotation(responseAnnotation)
      tracing.setStatus(
        span,
        StatusTranslator.translate(response.status.intValue())
      )

      ExecuteAfterResponse.onComplete(
        response,
        onFinish = () => tracing.endSpan(span),
        onFailure = _ => tracing.endSpan(span)
      )
    }
  }

}
