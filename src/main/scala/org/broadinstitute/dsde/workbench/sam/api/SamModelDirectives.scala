package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.{onSuccess, _}
import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller
import io.opencensus.scala.akka.http.TracingDirective.traceRequest
import io.opencensus.scala.Tracing.{startSpanWithParent}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.service.UserService
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

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
  def withSamRequestContext(): Directive1[SamRequestContext] =
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
        val newSpan = startSpanWithParent (spanName, parentSpan)
        val newSamRequestContext = samRequestContext.copy(parentSpan = Option(newSpan))
        provide(newSamRequestContext)

      case None => provide(SamRequestContext(None)) // for contexts without spans, do not start new spans
    }

}
