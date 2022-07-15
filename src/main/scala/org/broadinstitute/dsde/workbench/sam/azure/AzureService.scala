package org.broadinstitute.dsde.workbench.sam.azure

import akka.http.scaladsl.model.StatusCodes
import bio.terra.cloudres.azure.resourcemanager.common.Defaults
import bio.terra.cloudres.azure.resourcemanager.msi.data.CreateUserAssignedManagedIdentityRequestData
import cats.effect.IO
import com.azure.core.management.Region
import com.azure.core.util.Context
import com.azure.resourcemanager.resources.models.{GenericResource, ResourceGroup}
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchException, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.jdk.CollectionConverters._

class AzureService(crlService: CrlService,
                   directoryDAO: DirectoryDAO) {

  // Static region in which to create all managed identities.
  // Managed identities are regional resources in Azure but they can be used across
  // regions. So it's safe to just use a static region and not make this configurable.
  // See: https://docs.microsoft.com/en-us/azure/active-directory/managed-identities-azure-resources/managed-identities-faq#can-the-same-managed-identity-be-used-across-multiple-regions
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
        case None => createUserPetManagedIdentity(id, user, request, samRequestContext)
      }
    } yield pet
  }

  def getOrCreateUserPetManagedIdentityByEmail(email: WorkbenchEmail,
                                               request: GetOrCreatePetManagedIdentityRequest,
                                               samRequestContext: SamRequestContext): IO[(PetManagedIdentity, Boolean)] = {
    for {
      subjectOpt <- directoryDAO.loadSubjectFromEmail(email, samRequestContext)
      samUserOpt <- subjectOpt match {
        case Some(userId: WorkbenchUserId) => directoryDAO.loadUser(userId, samRequestContext)
        case _ => IO.pure(None)
      }
      samUser <- IO.fromOption(samUserOpt)(new WorkbenchException(s"Unknown user: ${email.value}"))
      res <- getOrCreateUserPetManagedIdentity(samUser, request, samRequestContext)
    } yield res
  }

  /**
    * Creates a pet managed identity in Azure and the Sam database.
    */
  private def createUserPetManagedIdentity(id: PetManagedIdentityId,
                                           user: SamUser,
                                           request: GetOrCreatePetManagedIdentityRequest,
                                           samRequestContext: SamRequestContext): IO[(PetManagedIdentity, Boolean)] = {
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

  /**
    * Resolves a managed resource group in Azure and returns the terra.billingProfileId tag value.
    * This is used for access control checks during route handling.
    */
  def getBillingProfileId(request: GetOrCreatePetManagedIdentityRequest): IO[Option[ResourceId]] =
    for {
      resourceManager <- crlService.buildResourceManager(request.tenantId, request.subscriptionId)
      mrg <- IO(resourceManager.resourceGroups().getByName(request.managedResourceGroupName.value)).attempt
    } yield mrg.toOption.flatMap(getBillingProfileFromTag)

  /**
    * Validates a managed resource group. This is only called at managed identity creation time.
    * Algorithm:
    * 1. Resolve the MRG in Azure
    * 2. Get the managed app id from the MRG
    * 3. Resolve the managed app as the publisher
    * 4. Get the managed app "plan" id
    * 4. Validate the plan id matches the configured value
    */
  private def validateManagedResourceGroup(request: GetOrCreatePetManagedIdentityRequest): IO[Unit] = {
    for {
      resourceManager <- crlService.buildResourceManager(request.tenantId, request.subscriptionId)
      mrg <- IO(resourceManager.resourceGroups().getByName(request.managedResourceGroupName.value)).attempt
      managedByOpt = mrg.toOption.flatMap(getManagedBy)
      managedBy <- IO.fromOption(managedByOpt)(
        new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, s"Validation failed: could not retrieve managed app for managed resource group ${request.managedResourceGroupName.value}"))
      )
      managedApp <- IO(resourceManager.genericResources().getById(managedBy)).attempt
      planIdOpt = managedApp.toOption.flatMap(getPlanId)
      planId <- IO.fromOption(planIdOpt)(
        new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, "Validation failed: could not retrieve plan for managed app"))
      )
      _ <- IO.raiseUnless(planId == crlService.getManagedAppPlanId)(
        new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Forbidden, "Validation failed: wrong managed app plan"))
      )
    } yield ()
  }

  /** Null-safe get managedBy field from a ResourceGroup. */
  private def getManagedBy(mrg: ResourceGroup): Option[String] =
    for {
      im <- Option(mrg.innerModel())
      mb <- Option(im.managedBy())
    } yield mb

  /** Null-safe get planId from a resource. */
  private def getPlanId(resource: GenericResource): Option[String] =
    Option(resource.plan()).map(_.name())

  /** Null-safe get billing profile tag from a ResourceGroup. */
  private def getBillingProfileFromTag(mrg: ResourceGroup): Option[ResourceId] =
    mrg.tags().asScala.get(billingProfileTag).map(ResourceId(_))

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
