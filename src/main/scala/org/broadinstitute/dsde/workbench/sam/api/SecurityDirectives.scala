package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.onSuccess
import akka.http.scaladsl.server.{Directive0, Directives}
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedResourceId, ResourceAction}
import org.broadinstitute.dsde.workbench.sam.service.PolicyEvaluatorService
import ImplicitConversions.ioOnSuccessMagnet

trait SecurityDirectives {
  def policyEvaluatorService: PolicyEvaluatorService

  def requireAction(resource: FullyQualifiedResourceId, action: ResourceAction, userId: WorkbenchUserId): Directive0 =
    requireOneOfAction(resource, Set(action), userId)

  def requireOneOfAction(resource: FullyQualifiedResourceId, requestedActions: Set[ResourceAction], userId: WorkbenchUserId): Directive0 =
    Directives.mapInnerRoute { innerRoute =>
      onSuccess(policyEvaluatorService.listUserResourceActions(resource, userId)) { actions =>
        if (hasAccess(requestedActions, actions)) innerRoute
        else if (actions.isEmpty)
          Directives.failWith(
            new WorkbenchExceptionWithErrorReport(
              ErrorReport(StatusCodes.NotFound, s"Resource ${resource.resourceTypeName.value}/${resource.resourceId.value} not found")))
        else
          Directives.failWith(
            new WorkbenchExceptionWithErrorReport(ErrorReport(
              StatusCodes.Forbidden,
              s"You may not perform any of ${requestedActions.mkString("[", ", ", "]").toString.toUpperCase} on ${resource.resourceTypeName.value}/${resource.resourceId.value}"
            )))
      }
    }

  private def hasAccess(requestedActions: Set[ResourceAction], actions: Set[ResourceAction]): Boolean =
    actions.intersect(requestedActions).nonEmpty
}
