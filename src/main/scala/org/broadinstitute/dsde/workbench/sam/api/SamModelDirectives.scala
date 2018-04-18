package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.{onSuccess, _}
import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.service.UserService

/**
 * Created by gpolumbo on 3/26/2018
 */
trait SamModelDirectives {
  val userService: UserService

  def withSubject(email: WorkbenchEmail): Directive1[WorkbenchSubject] = {
    onSuccess(userService.getSubjectFromEmail(email)).map {
      case Some(subject) => subject
      case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.BadRequest, s"${email} not found"))
    }
  }

  def withOptionalEntity[T](unmarshaller: FromRequestUnmarshaller[T]): Directive1[Option[T]] = {
    entity(as[String]).flatMap { stringEntity =>
      if(stringEntity == null || stringEntity.isEmpty) {
        provide(Option.empty[T])
      } else {
        entity(unmarshaller).flatMap(e => provide(Some(e)))
      }
    }
  }

}
