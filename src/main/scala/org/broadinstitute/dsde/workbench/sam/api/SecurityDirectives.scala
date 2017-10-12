package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.onSuccess
import akka.http.scaladsl.server.{Directive0, Directives}
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model.{Resource, ResourceAction, UserInfo}
import org.broadinstitute.dsde.workbench.sam.service.ResourceService

trait SecurityDirectives {
  val resourceService: ResourceService

  def requireAction(resource: Resource, action: ResourceAction, userInfo: UserInfo): Directive0 = {
    Directives.mapInnerRoute { innerRoute =>
      onSuccess(resourceService.listUserResourceActions(resource, userInfo)) { actions =>
        if(actions.contains(action)) innerRoute
        else if (actions.isEmpty) Directives.failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"Resource ${resource.resourceTypeName.value}/${resource.resourceId.value} not found")))
        else Directives.failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, s"You may not perform ${action.toString.toUpperCase} on ${resource.resourceTypeName.value}/${resource.resourceId.value}")))
      }
    }
  }
}
