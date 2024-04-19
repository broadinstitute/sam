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
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.service.CloudExtensions
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import spray.json.JsString

trait AzureRoutes extends SecurityDirectives with LazyLogging with SamRequestContextDirectives with SamModelDirectives {
  val azureService: Option[AzureService]

  def azureRoutes(samUser: SamUser, samRequestContext: SamRequestContext): Route =
    azureService
      .map { service =>
        pathPrefix("azure" / "v1") {
          path("user" / "petManagedIdentity") {
            postWithTelemetry(samRequestContext) {
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
              val workbenchEmail = WorkbenchEmail(userEmail)
              postWithTelemetry(samRequestContext, "userEmail" -> workbenchEmail) {
                requireCloudExtensionCreatePetAction(samUser, samRequestContext) {
                  loadSamUser(workbenchEmail, samRequestContext) { targetSamUser =>
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
            pathPrefix("actionManagedIdentity") {
              path(Segment / Segment / Segment / Segment) { (bpId, resourceTypeName, resourceId, action) =>
                val billingProfileId = BillingProfileId(bpId)
                val resource = FullyQualifiedResourceId(ResourceTypeName(resourceTypeName), ResourceId(resourceId))
                val resourceAction = ResourceAction(action)

                withNonAdminResourceType(resource.resourceTypeName) { resourceType =>
                  if (!resourceType.actionPatterns.map(ap => ResourceAction(ap.value)).contains(resourceAction)) {
                    throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"action $action not found"))
                  }
                  pathEndOrSingleSlash {
                    postWithTelemetry(
                      samRequestContext,
                      "billingProfileId" -> billingProfileId,
                      "resourceType" -> resource.resourceTypeName,
                      "resource" -> resource.resourceId,
                      "action" -> resourceAction
                    ) {
                      complete {
                        service.getOrCreateActionManagedIdentity(resource, resourceAction, billingProfileId, samUser, samRequestContext).map {
                          case (ami, created) =>
                            val status = if (created) StatusCodes.Created else StatusCodes.OK
                            status -> ami
                        }
                      }
                    }
                  }
                }
              } ~
                path(Segment / Segment / Segment) { (resourceTypeName, resourceId, action) =>
                  val resource = FullyQualifiedResourceId(ResourceTypeName(resourceTypeName), ResourceId(resourceId))
                  val resourceAction = ResourceAction(action)

                  withNonAdminResourceType(resource.resourceTypeName) { resourceType =>
                    if (!resourceType.actionPatterns.map(ap => ResourceAction(ap.value)).contains(resourceAction)) {
                      throw new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"action $action not found"))
                    }
                    pathEndOrSingleSlash {
                      getWithTelemetry(
                        samRequestContext,
                        "resourceType" -> resource.resourceTypeName,
                        "resource" -> resource.resourceId,
                        "action" -> resourceAction
                      ) {
                        complete {
                          service.getActionManagedIdentity(resource, resourceAction, samUser, samRequestContext).map {
                            case Some(actionManagedIdentity) => StatusCodes.OK -> actionManagedIdentity
                            case None =>
                              throw new WorkbenchExceptionWithErrorReport(
                                ErrorReport(StatusCodes.NotFound, s"Action Managed identity for [$resourceAction] on [$resource] not found")
                              )
                          }
                        }
                      }
                    }
                  }
                }
            } ~
            path("billingProfile" / Segment / "managedResourceGroup") { billingProfileId =>
              val billingProfileResourceId = ResourceId(billingProfileId)
              val billingProfileIdParam = "billingProfileId" -> billingProfileResourceId
              postWithTelemetry(samRequestContext, billingProfileIdParam) {
                entity(as[ManagedResourceGroupCoordinates]) { mrgCoords =>
                  requireAction(
                    FullyQualifiedResourceId(SamResourceTypes.spendProfile, billingProfileResourceId),
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
                deleteWithTelemetry(samRequestContext, billingProfileIdParam) {
                  requireAction(
                    FullyQualifiedResourceId(SamResourceTypes.spendProfile, billingProfileResourceId),
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

  // Validates the provided SamUser has 'create-pet' permission on the spend-profile resource represented by the
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
          SamResourceActions.createPet,
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
