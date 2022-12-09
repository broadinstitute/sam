package org.broadinstitute.dsde.workbench.sam.azure

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Directive1, ExceptionHandler, Route}
import cats.syntax.all._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.ImplicitConversions.ioOnSuccessMagnet
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.api.{SecurityDirectives, _}
import org.broadinstitute.dsde.workbench.sam.azure.AzureJsonSupport._
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import spray.json.JsString

trait AzureRoutes extends SecurityDirectives with LazyLogging {
  val azureService: Option[AzureService]

  def azureRoutes(samUser: SamUser, samRequestContext: SamRequestContext): Route =
    azureService
      .map { service =>
        pathPrefix("azure" / "v1") {
          path("user" / "petManagedIdentity") {
            post {
              entity(as[GetOrCreatePetManagedIdentityRequest]) { request =>
                requireUserCreatePetAction(request, samUser, samRequestContext) {
                  complete {
                    service.getOrCreateUserPetManagedIdentity(samUser, request, samRequestContext).map { case (pet, created) =>
                      val status = if (created) StatusCodes.Created else StatusCodes.OK
                      status -> JsString(pet.objectId.value)
                    }
                  }
                }
              }
            }
          } ~
            path("petManagedIdentity" / Segment) { userEmail =>
              post {
                requireCloudExtensionCreatePetAction(samUser, samRequestContext) {
                  loadSamUser(WorkbenchEmail(userEmail), samRequestContext) { targetSamUser =>
                    entity(as[GetOrCreatePetManagedIdentityRequest]) { request =>
                      requireUserCreatePetAction(request, targetSamUser, samRequestContext) {
                        complete {
                          service.getOrCreateUserPetManagedIdentity(targetSamUser, request, samRequestContext).map { case (pet, created) =>
                            val status = if (created) StatusCodes.Created else StatusCodes.OK
                            status -> JsString(pet.objectId.value)
                          }
                        }
                      }
                    }
                  }
                }
              }
            } ~
            path("billingProfile" / Segment / "managedResourceGroup") { billingProfileId =>
              post {
                entity(as[ManagedResourceGroupCoordinates]) { mrgCoords =>
                  requireAction(
                    FullyQualifiedResourceId(SamResourceTypes.spendProfile, ResourceId(billingProfileId)),
                    SamResourceActions.setManagedResourceGroup,
                    samUser.id,
                    samRequestContext
                  ) {
                    complete {
                      service
                        .createManagedResourceGroup(ManagedResourceGroup(mrgCoords, BillingProfileId(billingProfileId)), samRequestContext)
                        .map(_ => StatusCodes.Created)
                    }
                  }
                }
              } ~
                delete {
                  requireAction(
                    FullyQualifiedResourceId(SamResourceTypes.spendProfile, ResourceId(billingProfileId)),
                    SamResourceActions.delete,
                    samUser.id,
                    samRequestContext
                  ) {
                    complete {
                      service.deleteManagedResourceGroup(BillingProfileId(billingProfileId), samRequestContext).map(_ => StatusCodes.NoContent)
                    }
                  }
                }
            }
        }
      }
      .getOrElse(reject)

  // Validates the provided SamUser has 'link' permission on the spend-profile resource represented by the
  // GetOrCreatePetManagedIdentityRequest
  private def requireUserCreatePetAction(request: GetOrCreatePetManagedIdentityRequest, samUser: SamUser, samRequestContext: SamRequestContext): Directive0 = {
    val failWithForbidden = failWith(
      new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, "User is not permitted to create pet identity in resource group."))
    )

    val maskNotFound = handleExceptions(ExceptionHandler {
      case withErrorReport: WorkbenchExceptionWithErrorReport if withErrorReport.errorReport.statusCode.contains(StatusCodes.NotFound) =>
        failWithForbidden
    })

    onSuccess(azureService.flatTraverse(_.getBillingProfileId(request, samRequestContext))).flatMap {
      case Some(resourceId) =>
        maskNotFound & requireAction(
          FullyQualifiedResourceId(SamResourceTypes.spendProfile, resourceId.asResourceId),
          SamResourceActions.link,
          samUser.id,
          samRequestContext
        )
      case None => failWithForbidden
    }
  }

  // Validates the provided SamUser has 'getPetManagedIdentityAction' on the 'azure' cloud-extension resource.
  private def requireCloudExtensionCreatePetAction(samUser: SamUser, samRequestContext: SamRequestContext): Directive0 =
    requireAction(
      FullyQualifiedResourceId(CloudExtensions.resourceTypeName, AzureExtensions.resourceId),
      AzureExtensions.getPetManagedIdentityAction,
      samUser.id,
      samRequestContext
    )

  // Loads a SamUser from the database by email. Fails with 404 if not found.
  private def loadSamUser(email: WorkbenchEmail, samRequestContext: SamRequestContext): Directive1[SamUser] =
    onSuccess(azureService.flatTraverse(_.getSamUser(email, samRequestContext))).flatMap {
      case Some(samUser) => provide(samUser)
      case None =>
        failWith(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"User ${email.value} not found")))
    }
}
