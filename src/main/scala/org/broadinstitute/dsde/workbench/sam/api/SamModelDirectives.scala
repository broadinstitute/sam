package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.{Directive1, ExceptionHandler}
import akka.http.scaladsl.server.Directives.{onSuccess, _}
import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller
import io.opencensus.scala.Tracing
import io.opencensus.scala.akka.http.TracingDirective.traceRequest
import io.opencensus.scala.Tracing.startSpanWithParent
import io.opencensus.scala.akka.http.trace.HttpExtractors._
import io.opencensus.scala.akka.http.utils.ExecuteAfterResponse
import io.opencensus.scala.http.{HttpAttributes, StatusTranslator}
import io.opencensus.trace.{Span, Status}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.service.UserService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.util.control.NonFatal

/**
  * Created by gpolumbo on 3/26/2018
  */
trait SamModelDirectives {
  val userService: UserService

  def withSubject(email: WorkbenchEmail, samRequestContext: SamRequestContext): Directive1[WorkbenchSubject] =
    onSuccess(userService.getSubjectFromEmail(email, samRequestContext)).map {
      case Some(subject) => subject
      case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"${email} not found"))
    }

  def withOptionalEntity[T](unmarshaller: FromRequestUnmarshaller[T]): Directive1[Option[T]] =
    entity(as[String]).flatMap { stringEntity =>
      if (stringEntity == null || stringEntity.isEmpty) {
        provide(Option.empty[T])
      } else {
        entity(unmarshaller).flatMap(e => provide(Some(e)))
      }
    }

  /**
    * Provides a new SamRequestContext with a root tracing span.
    */
  def withSamRequestContext: Directive1[SamRequestContext] =
    traceRequest.map { span => SamRequestContext(Option(span)) }

  /**
    * Provides a new SamRequestContext with a tracing span that is a child of the existing SamRequestContext's `parentSpan`.
    *
    * @param spanName name of the new parentSpan in the new SamRequestContext. This span is a child of the existing parentSpan.
    * @param samRequestContext the existing samRequestContext.
    */
  def withNewTraceSpan(spanName: String, samRequestContext: SamRequestContext): Directive1[SamRequestContext] =
    samRequestContext.parentSpan match {
      case Some (parentSpan) =>
        val newSpan = startSpanWithParent(spanName, parentSpan)
        val newSamRequestContext = samRequestContext.copy(parentSpan = Option(newSpan))
        recordSuccess(newSpan) & recordException(newSpan) & provide(newSamRequestContext)

      case None => provide(SamRequestContext(None)) // for contexts without spans, do not start new spans
    }

  protected def tracing: Tracing = Tracing

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
