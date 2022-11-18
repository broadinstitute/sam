package org.broadinstitute.dsde.workbench.sam.azure

import akka.http.scaladsl.model.StatusCodes
import bio.terra.cloudres.azure.resourcemanager.common.Defaults
import bio.terra.cloudres.azure.resourcemanager.msi.data.CreateUserAssignedManagedIdentityRequestData
import cats.effect.IO
import com.azure.core.management.Region
import com.azure.core.util.Context
import com.azure.resourcemanager.managedapplications.models.Application
import com.azure.resourcemanager.resources.ResourceManager
import com.azure.resourcemanager.resources.models.ResourceGroup
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchException, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.config.ManagedAppPlan
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AzureManagedResourceGroupDAO, DirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.util.OpenCensusIOUtils._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.jdk.CollectionConverters._

class AzureService(crlService: CrlService, directoryDAO: DirectoryDAO, azureManagedResourceGroupDAO: AzureManagedResourceGroupDAO) {

  // Static region in which to create all managed identities.
  // Managed identities are regional resources in Azure but they can be used across
  // regions. So it's safe to just use a static region and not make this configurable.
  // See: https://docs.microsoft.com/en-us/azure/active-directory/managed-identities-azure-resources/managed-identities-faq#can-the-same-managed-identity-be-used-across-multiple-regions
  private val managedIdentityRegion = Region.US_EAST

  // Tag on the MRG to specify the Sam billing-profile id
  private val billingProfileTag = "terra.billingProfileId"

  /** This is specifically a val so that the stack trace does not leak information about why this error was thrown. Because it is a val, the stack trace is
    * constant. If it were a def, the stack trace would include the line number where the error was thrown.
    */
  private val managedAppValidationFailure = new WorkbenchExceptionWithErrorReport(
    ErrorReport(
      StatusCodes.Forbidden,
      "Specified manged resource group invalid. Possible reasons include resource group does not exist, it is not " +
        "associated to an application, the application's plan is not supported or the user is not listed as authorized."
    )
  )

  def createManagedResourceGroup(managedResourceGroup: ManagedResourceGroup, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      _ <- validateManagedResourceGroup(managedResourceGroup.managedResourceGroupCoordinates, samRequestContext)

      existingByCoords <- azureManagedResourceGroupDAO.getManagedResourceGroupByCoordinates(
        managedResourceGroup.managedResourceGroupCoordinates,
        samRequestContext
      )
      _ <- IO.raiseWhen(existingByCoords.isDefined)(
        new WorkbenchExceptionWithErrorReport(
          ErrorReport(StatusCodes.Conflict, s"managed resource group ${managedResourceGroup.managedResourceGroupCoordinates} already exists")
        )
      )

      existingByBillingProfile <- azureManagedResourceGroupDAO.getManagedResourceGroupByBillingProfileId(
        managedResourceGroup.billingProfileId,
        samRequestContext
      )
      _ <- IO.raiseWhen(existingByBillingProfile.isDefined)(
        new WorkbenchExceptionWithErrorReport(
          ErrorReport(StatusCodes.Conflict, s"managed resource group for ${managedResourceGroup.billingProfileId} already exists")
        )
      )

      _ <- azureManagedResourceGroupDAO.insertManagedResourceGroup(managedResourceGroup, samRequestContext)
    } yield ()

  /** Looks up a pet managed identity from the database, or creates it if one does not exist.
    */
  def getOrCreateUserPetManagedIdentity(
      user: SamUser,
      request: GetOrCreatePetManagedIdentityRequest,
      samRequestContext: SamRequestContext
  ): IO[(PetManagedIdentity, Boolean)] = {
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

  /** Creates a pet managed identity in Azure and the Sam database.
    */
  private def createUserPetManagedIdentity(
      id: PetManagedIdentityId,
      user: SamUser,
      request: GetOrCreatePetManagedIdentityRequest,
      samRequestContext: SamRequestContext
  ): IO[(PetManagedIdentity, Boolean)] =
    for {
      _ <- validateManagedResourceGroup(request.toManagedResourceGroupCoordinates, samRequestContext, false)
      manager <- crlService.buildMsiManager(request.tenantId, request.subscriptionId)
      petName = toManagedIdentityNameFromUser(user)
      context = managedIdentityContext(request, petName)
      azureUami <- IO(
        manager
          .identities()
          .define(petName.value)
          .withRegion(managedIdentityRegion)
          .withExistingResourceGroup(request.managedResourceGroupName.value)
          .withTags(managedIdentityTags(user).asJava)
          .create(context)
      )
      petToCreate = PetManagedIdentity(id, ManagedIdentityObjectId(azureUami.id()), ManagedIdentityDisplayName(azureUami.name()))
      createdPet <- directoryDAO.createPetManagedIdentity(petToCreate, samRequestContext)
    } yield (createdPet, true)

  /** Loads a SamUser from the database by email.
    */
  def getSamUser(email: WorkbenchEmail, samRequestContext: SamRequestContext): IO[Option[SamUser]] =
    for {
      subjectOpt <- directoryDAO.loadSubjectFromEmail(email, samRequestContext)
      samUserOpt <- subjectOpt match {
        case Some(userId: WorkbenchUserId) => directoryDAO.loadUser(userId, samRequestContext)
        case _ => IO.none
      }
    } yield samUserOpt

  /** Resolves a managed resource group in Azure and returns the terra.billingProfileId tag value. This is used for access control checks during route handling.
    */
  def getBillingProfileId(request: GetOrCreatePetManagedIdentityRequest): IO[Option[ResourceId]] =
    for {
      resourceManager <- crlService.buildResourceManager(request.tenantId, request.subscriptionId)
      mrg <- IO(resourceManager.resourceGroups().getByName(request.managedResourceGroupName.value)).attempt
    } yield mrg.toOption.flatMap(getBillingProfileFromTag)

  /** Validates a managed resource group. Algorithm:
    *   1. Resolve the MRG in Azure 2. Get the managed app id from the MRG 3. Resolve the managed app 4. Get the managed app "plan" name and publisher 5.
    *      Validate the plan name and publisher matches a configured value 6. Validate that the caller is on the list of authorized users for the app
    */
  private def validateManagedResourceGroup(
      mrgCoords: ManagedResourceGroupCoordinates,
      samRequestContext: SamRequestContext,
      validateUser: Boolean = true
  ): IO[Unit] =
    traceIOWithContext("validateManagedResourceGroup", samRequestContext) { _ =>
      for {
        resourceManager <- crlService.buildResourceManager(mrgCoords.tenantId, mrgCoords.subscriptionId)
        mrg <- lookupMrg(mrgCoords, resourceManager)
        appManager <- crlService.buildApplicationManager(mrgCoords.tenantId, mrgCoords.subscriptionId)
        appsInSubscription <- IO(appManager.applications().list().asScala)
        managedApp <- IO.fromOption(appsInSubscription.find(_.managedResourceGroupId() == mrg.id()))(managedAppValidationFailure)
        plan <- validatePlan(managedApp, crlService.getManagedAppPlans)
        _ <- if (validateUser) validateAuthorizedAppUser(managedApp, plan, samRequestContext) else IO.unit
      } yield ()
    }

  /** The users authorized to setup a managed application are stored as a comma separated list of email addresses in the parameters of the application. The
    * azure api is java so this code needs to deal with possible nulls and java Maps. Also the application parameters are untyped, fun.
    * @param app
    * @param plan
    * @param samRequestContext
    * @return
    */
  private def validateAuthorizedAppUser(app: Application, plan: ManagedAppPlan, samRequestContext: SamRequestContext): IO[Unit] = {
    val authorizedUsersValue = for {
      parametersObj <- Option(app.parameters()) if parametersObj.isInstanceOf[java.util.Map[_, _]]
      parametersMap = parametersObj.asInstanceOf[java.util.Map[_, _]]
      paramValuesObj <- Option(parametersMap.get(plan.authorizedUserKey)) if paramValuesObj.isInstanceOf[java.util.Map[_, _]]
      paramValues = paramValuesObj.asInstanceOf[java.util.Map[_, _]]
      authorizedUsersValue <- Option(paramValues.get("value"))
    } yield authorizedUsersValue.toString

    for {
      authorizedUsersString <- IO.fromOption(authorizedUsersValue)(managedAppValidationFailure)
      user <- IO.fromOption(samRequestContext.samUser)(
        // this exception is different from the others because it is a coding bug, the user should always be present here
        new WorkbenchException("user is missing in call to validateAuthorizedAppUser")
      )
      authorizedUsers = authorizedUsersString.split(",").map(_.trim.toLowerCase)
      _ <- IO.raiseUnless(authorizedUsers.contains(user.email.value.toLowerCase))(managedAppValidationFailure)
    } yield ()
  }

  private def validatePlan(managedApp: Application, allPlans: Seq[ManagedAppPlan]) = {
    val maybePlan = for {
      applicationPlan <- Option(managedApp.plan())
      matchingPlan <- allPlans.find(p => p.name == applicationPlan.name() && p.publisher == applicationPlan.publisher())
    } yield matchingPlan

    IO.fromOption(maybePlan)(managedAppValidationFailure)
  }

  private def lookupMrg(mrgCoords: ManagedResourceGroupCoordinates, resourceManager: ResourceManager) =
    IO(resourceManager.resourceGroups().getByName(mrgCoords.managedResourceGroupName.value)).handleErrorWith { case t: Throwable =>
      IO.raiseError(managedAppValidationFailure)
    }

  /** Null-safe get billing profile tag from a ResourceGroup. */
  private def getBillingProfileFromTag(mrg: ResourceGroup): Option[ResourceId] =
    mrg.tags().asScala.get(billingProfileTag).map(ResourceId(_))

  private def managedIdentityTags(user: SamUser): Map[String, String] =
    Map("samUserId" -> user.id.value, "samUserEmail" -> user.email.value)

  private def managedIdentityContext(request: GetOrCreatePetManagedIdentityRequest, petName: ManagedIdentityDisplayName): Context =
    Defaults.buildContext(
      CreateUserAssignedManagedIdentityRequestData
        .builder()
        .setTenantId(request.tenantId.value)
        .setSubscriptionId(request.subscriptionId.value)
        .setResourceGroupName(request.managedResourceGroupName.value)
        .setName(petName.value)
        .setRegion(managedIdentityRegion)
        .build()
    )

  private def toManagedIdentityNameFromUser(user: SamUser): ManagedIdentityDisplayName =
    ManagedIdentityDisplayName(s"pet-${user.id.value}")

}
