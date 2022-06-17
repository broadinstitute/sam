package org.broadinstitute.dsde.workbench.sam.azure

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Directives, Route}
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.ImplicitConversions.ioOnSuccessMagnet
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.api.{SecurityDirectives, _}
import org.broadinstitute.dsde.workbench.sam.azure.AzureJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.{PolicyEvaluatorService, ResourceService}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

class AzureRoutes(azureService: AzureService,
                  val policyEvaluatorService: PolicyEvaluatorService,
                  val resourceService: ResourceService) extends SecurityDirectives {

  def routes(samUser: SamUser, samRequestContext: SamRequestContext): Route =
    path("azure" / "v1" / "user" / "petManagedIdentity") {
      post {
        entity(as[GetOrCreatePetManagedIdentityRequest]) { request =>
          requireCreatePetAction(request, samUser, samRequestContext) {
            complete {
              azureService.getOrCreateUserPetManagedIdentity(samUser, request, samRequestContext).map { case (pet, created) =>
                val status = if (created) StatusCodes.Created else StatusCodes.OK
                status -> pet.objectId.value
              }
            }
          }
        }
      }
    }

  // Given a GetOrCreatePetManagedIdentityRequest, looks up the billing profile resource
  // and  validates the user has 'link' permission.
  private def requireCreatePetAction(request: GetOrCreatePetManagedIdentityRequest, samUser: SamUser, samRequestContext: SamRequestContext): Directive0 =
    onSuccess(azureService.getBillingProfileId(request)).flatMap {
      case Some(resourceId) =>
        requireAction(FullyQualifiedResourceId(SamResourceTypes.spendProfile, resourceId), SamResourceActions.link, samUser.id, samRequestContext)
      case None =>
        Directives.failWith(
          new WorkbenchExceptionWithErrorReport(
            ErrorReport(StatusCodes.NotFound, s"Managed resource group ${request.managedResourceGroupName.value} not found")))

    }
}
