package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.onSuccess
import akka.http.scaladsl.server.{Directive0, Directives}
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedResourceId, ResourceAction}
import org.broadinstitute.dsde.workbench.sam.service.{PolicyEvaluatorService, ResourceService}
import ImplicitConversions.ioOnSuccessMagnet
import cats.effect.IO

trait SecurityDirectives {
  def policyEvaluatorService: PolicyEvaluatorService
  def resourceService: ResourceService

  def requireAction(resource: FullyQualifiedResourceId, action: ResourceAction, userId: WorkbenchUserId): Directive0 = {
    val requestedActions = Set(action)

    Directives.mapInnerRoute { innerRoute =>
      onSuccess(policyEvaluatorService.hasPermission(resource, action, userId)) { hasPermission =>
        if (hasPermission) {
          innerRoute
        } else {

          // in the case where we don't have the required action, we need to figure out if we should return
          // a Not Found (you have no access) vs a Forbidden (you have access, just not the right kind)
          onSuccess(policyEvaluatorService.listResourceAccessPoliciesForUser(resource, userId)) { policies =>

            if (policies.isEmpty) {
              Directives.failWith(
                new WorkbenchExceptionWithErrorReport(
                  ErrorReport(StatusCodes.NotFound, s"Resource ${resource.resourceTypeName.value}/${resource.resourceId.value} not found")))
            } else {
              Directives.failWith(
                new WorkbenchExceptionWithErrorReport(ErrorReport(
                  StatusCodes.Forbidden,
                  s"You may not perform any of ${requestedActions.mkString("[", ", ", "]").toString.toUpperCase} on ${resource.resourceTypeName.value}/${resource.resourceId.value}"
                )))


            }
          }
        }
      }
    }
  }


  def requireOneOfAction(resource: FullyQualifiedResourceId, requestedActions: Set[ResourceAction], userId: WorkbenchUserId): Directive0 =
    Directives.mapInnerRoute { innerRoute =>
      onSuccess(listActions(resource, userId, requestedActions)) { actions =>
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

  private def listActions(resource: FullyQualifiedResourceId, userId: WorkbenchUserId, requestedActions: Set[ResourceAction]): IO[Set[ResourceAction]] =
    // this is optimized for the case where the user has permission since that is the usual case
    // if the first attempt shows the user does not have permission, force a second attempt
    for {
      actionsAttempt1 <- policyEvaluatorService.listUserResourceActions(resource, userId, force = false)
      actionsAttempt2 <- if (hasAccess(requestedActions, actionsAttempt1)) IO.pure(actionsAttempt1)
      else policyEvaluatorService.listUserResourceActions(resource, userId, force = true)
    } yield actionsAttempt2

  private def hasAccess(requestedActions: Set[ResourceAction], actions: Set[ResourceAction]): Boolean =
    actions.intersect(requestedActions).nonEmpty
}
