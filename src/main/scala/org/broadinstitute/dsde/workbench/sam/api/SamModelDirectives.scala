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

  def withSamRequestContext: Directive1[SamRequestContext] =
    traceRequest.map { span => SamRequestContext(Option(span)) }

  // todo: overload? or check for optional name/samrequestcontext?
  def withParentSamRequestContext(name: String, samRequestContext: SamRequestContext): Directive1[SamRequestContext] =
    samRequestContext.parentSpan match {
      case Some (parentSpan) =>
        val newSpan = startSpanWithParent (name, parentSpan)
        val newSamRequestContext = SamRequestContext(Option(newSpan))
        provide(newSamRequestContext)

      // todo: should we use name in the new span here?
      case None => traceRequest.map {span => SamRequestContext(Option(span))}
    }

}
