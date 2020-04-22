package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.onSuccess
import akka.http.scaladsl.server.{Directive0, Directives}
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedResourceId, ResourceAction}
import org.broadinstitute.dsde.workbench.sam.service.PolicyEvaluatorService
import ImplicitConversions.ioOnSuccessMagnet
import cats.implicits._
import cats.effect.IO
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

trait SecurityDirectives {
  def policyEvaluatorService: PolicyEvaluatorService

  def requireAction(resource: FullyQualifiedResourceId, action: ResourceAction, userId: WorkbenchUserId, traceContext: SamRequestContext = null): Directive0 = //todo: create a root span here instead of allowing null?
    requireOneOfAction(resource, Set(action), userId)

  def requireOneOfAction(resource: FullyQualifiedResourceId, requestedActions: Set[ResourceAction], userId: WorkbenchUserId, traceContext: SamRequestContext = null): Directive0 = //todo: create a root span here instead of allowing null?
    Directives.mapInnerRoute { innerRoute =>
      onSuccess(hasPermissionOneOf(resource, requestedActions, userId)) { hasPermission =>
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

  private def hasPermissionOneOf(resource: FullyQualifiedResourceId, actions: Iterable[ResourceAction], userId: WorkbenchUserId): IO[Boolean] =
    //  first quickly check if we have permission using the shallow check across all actions, then try the full check
    for {
      attempt1 <- actions.toList.existsM(policyEvaluatorService.hasPermissionShallowCheck(resource, _, userId))
      attempt2 <- if (attempt1) IO.pure(attempt1) else actions.toList.existsM(policyEvaluatorService.hasPermissionFullCheck(resource, _, userId))
    } yield {
      attempt2
    }
}
