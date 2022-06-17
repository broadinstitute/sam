package org.broadinstitute.dsde.workbench.sam.azure

import akka.http.scaladsl.model.StatusCodes
import bio.terra.cloudres.azure.resourcemanager.common.Defaults
import bio.terra.cloudres.azure.resourcemanager.msi.data.CreateUserAssignedManagedIdentityRequestData
import cats.effect.IO
import com.azure.core.management.Region
import com.azure.core.util.Context
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.jdk.CollectionConverters._

class AzureService(crlService: CrlService,
                   directoryDAO: DirectoryDAO) {
  // Static region in which to create all managed identities
  private val managedIdentityRegion = Region.US_EAST
  // Tag on the MRG to specify the Sam billing-profile id
  private val billingProfileTag = "terra.billingProfileId"

  /**
    * Looks up a pet managed identity from the database, or creates it if one does not exist.
    */
  def getOrCreateUserPetManagedIdentity(user: SamUser,
                                        request: GetOrCreatePetManagedIdentityRequest,
                                        samRequestContext: SamRequestContext): IO[(PetManagedIdentity, Boolean)] = {
    val id = PetManagedIdentityId(user.id, request.tenantId, request.subscriptionId, request.managedResourceGroupName)
    for {
      existingPetOpt <- directoryDAO.loadPetManagedIdentity(id, samRequestContext)
      pet <- existingPetOpt match {
        // pet exists in Sam DB - return it
        case Some(p) => IO.pure((p, false))
        // pet does not exist in Sam DB - create it
        case None =>
          for {
            _ <- validateManagedResourceGroup(request)
            manager <- crlService.buildMsiManager(request.tenantId, request.subscriptionId)
            petName = toManagedIdentityNameFromUser(user)
            context = managedIdentityContext(request, petName)
            azureUami <- IO(manager.identities().define(petName.value)
              .withRegion(managedIdentityRegion)
              .withExistingResourceGroup(request.managedResourceGroupName.value)
              .withTags(managedIdentityTags(user).asJava)
              .create(context))
            petToCreate = PetManagedIdentity(id, ManagedIdentityObjectId(azureUami.id()), ManagedIdentityDisplayName(azureUami.name()))
            createdPet <- directoryDAO.createPetManagedIdentity(petToCreate, samRequestContext)
          } yield (createdPet, true)
      }
    } yield pet
  }

  /**
    * Resolves a managed resource group in Azure and returns the terra.billingProfileId tag value.
    * This is used for access control checks.
    */
  def getBillingProfileId(request: GetOrCreatePetManagedIdentityRequest): IO[Option[ResourceId]] = {
    for {
      resourceManager <- crlService.buildResourceManager(request.tenantId, request.subscriptionId)
      mrg <- IO(resourceManager.resourceGroups().getByName(request.managedResourceGroupName.value)).attempt
      billingProfileOpt = mrg.toOption.flatMap(_.tags().asScala.get(billingProfileTag))
    } yield billingProfileOpt.map(ResourceId(_))
  }

  /**
    * Validates a managed resource group. Algorithm:
    * 1. Resolve the MRG as the publisher
    * 2. Get the managed app id from the MRG
    * 3. Resolve the managed app as the publisher
    * 4. Get the managed app "plan" id
    * 4. Validate the plan id matches the configured value
    */
  private def validateManagedResourceGroup(request: GetOrCreatePetManagedIdentityRequest): IO[Unit] = {
    for {
      resourceManager <- crlService.buildResourceManager(request.tenantId, request.subscriptionId)
      resolvedMrg <- IO(resourceManager.resourceGroups().getByName(request.managedResourceGroupName.value)).attempt
      managedByOpt = for {
        mrg <- resolvedMrg.toOption
        im <- Option(mrg.innerModel())
        mb <- Option(im.managedBy())
      } yield mb
      managedBy <- IO.fromOption(managedByOpt)(
        new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, s"Validation failed: could not retrieve managed app for managed resource group ${request.managedResourceGroupName.value}"))
      )
      resolvedManagedApp <- IO(resourceManager.genericResources().getById(managedBy)).attempt
      planIdOpt = for {
        ma <- resolvedManagedApp.toOption
        p <- Option(ma.plan())
      } yield p.name()
      planId <- IO.fromOption(planIdOpt)(
        new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, "Validation failed: could not retrieve plan for managed app"))
      )
      _ <- IO.raiseUnless(planId == crlService.getManagedAppPlanId)(
        new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, "Validation failed: wrong managed app plan"))
      )
    } yield ()
  }

  private def managedIdentityTags(user: SamUser): Map[String, String] = {
    Map("samUserId" -> user.id.value, "samUserEmail" -> user.email.value)
  }

  private def managedIdentityContext(request: GetOrCreatePetManagedIdentityRequest, petName: ManagedIdentityDisplayName): Context = {
    Defaults.buildContext(
      CreateUserAssignedManagedIdentityRequestData.builder()
        .setTenantId(request.tenantId.value)
        .setSubscriptionId(request.subscriptionId.value)
        .setResourceGroupName(request.managedResourceGroupName.value)
        .setName(petName.value)
        .setRegion(managedIdentityRegion)
        .build())
  }

  private def toManagedIdentityNameFromUser(user: SamUser): ManagedIdentityDisplayName = {
    ManagedIdentityDisplayName(s"pet-${user.id.value}")
  }

}
