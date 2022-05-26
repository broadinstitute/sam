package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.{onSuccess, _}
import akka.http.scaladsl.unmarshalling.FromRequestUnmarshaller
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.ImplicitConversions.ioOnSuccessMagnet
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model.SamResourceTypes.resourceTypeAdminName
import org.broadinstitute.dsde.workbench.sam.model.{ResourceType, ResourceTypeName}
import org.broadinstitute.dsde.workbench.sam.service.{ResourceService, UserService}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

/**
  * Created by gpolumbo on 3/26/2018
  */
trait SamModelDirectives {
  val userService: UserService
  val resourceService: ResourceService

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

  def withResourceType(name: ResourceTypeName): Directive1[ResourceType] =
    onSuccess(resourceService.getResourceType(name)).map {
      case Some(resourceType) => resourceType
      case None => throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"resource type ${name.value} not found"))
    }

  def withNonAdminResourceType(name: ResourceTypeName): Directive1[ResourceType] =
    if (name != resourceTypeAdminName)
      withResourceType(name) else
      failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(
        StatusCodes.BadRequest,
        """You must use routes prefixed "/api/resourceTypeAdmin/v1/resourceTypes" to access policies on "resource_type_admin" resources."""
      )))

}
