package org.broadinstitute.dsde.workbench.sam.azure

import akka.http.scaladsl.model.StatusCodes
import bio.terra.cloudres.azure.resourcemanager.common.Defaults
import bio.terra.cloudres.azure.resourcemanager.msi.data.CreateUserAssignedManagedIdentityRequestData
import cats.effect.IO
import cats.implicits.toTraverseOps
import com.azure.core.management.Region
import com.azure.core.util.Context
import com.azure.resourcemanager.managedapplications.models.Application
import com.azure.resourcemanager.resources.ResourceManager
import com.azure.resourcemanager.resources.models.ResourceGroup
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchEmail, WorkbenchException, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam._
import org.broadinstitute.dsde.workbench.sam.config.{AzureMarketPlace, AzureServiceCatalog, AzureServicesConfig, ManagedAppPlan}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AzureManagedResourceGroupDAO, DirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model.{FullyQualifiedResourceId, ResourceAction}
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.util.OpenTelemetryIOUtils._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.jdk.CollectionConverters._

class AzureService(
    config: AzureServicesConfig,
    crlService: CrlService,
    directoryDAO: DirectoryDAO,
    azureManagedResourceGroupDAO: AzureManagedResourceGroupDAO
) {

  // Tag on the MRG to specify the Sam billing-profile id
  private val billingProfileTag = "terra.billingProfileId"

  /** This is specifically a val so that the stack trace does not leak information about why this error was thrown. Because it is a val, the stack trace is
    * constant. If it were a def, the stack trace would include the line number where the error was thrown.
    */
  private val managedAppValidationFailure = new WorkbenchExceptionWithErrorReport(
    ErrorReport(
      StatusCodes.Forbidden,
      "Specified managed resource group invalid. Possible reasons include resource group does not exist, it is not " +
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

  /** Delete the managed resource group for a given billing profile
    */
  def deleteManagedResourceGroup(billingProfileId: BillingProfileId, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      existing <- azureManagedResourceGroupDAO.getManagedResourceGroupByBillingProfileId(billingProfileId, samRequestContext)
      _ <- IO.raiseWhen(existing.isEmpty)(
        new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.NotFound, s"managed resource group for profile ${billingProfileId} not found"))
      )
      actionManagedIdentities <- directoryDAO.getAllActionManagedIdentitiesForBillingProfile(billingProfileId, samRequestContext)
      _ <- actionManagedIdentities.toList.traverse { ami =>
        deleteActionManagedIdentity(ami.id, samRequestContext)
      }
      _ <- azureManagedResourceGroupDAO.deleteManagedResourceGroup(
        billingProfileId,
        samRequestContext
      )
    } yield {}

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
        case None => createUserPetManagedIdentity(id, user, request.toManagedResourceGroupCoordinates, samRequestContext)
      }
    } yield pet
  }

  /** Creates a pet managed identity in Azure and the Sam database.
    */
  private def createUserPetManagedIdentity(
      id: PetManagedIdentityId,
      user: SamUser,
      mrgCoords: ManagedResourceGroupCoordinates,
      samRequestContext: SamRequestContext
  ): IO[(PetManagedIdentity, Boolean)] =
    for {
      msiManager <- crlService.buildMsiManager(mrgCoords.tenantId, mrgCoords.subscriptionId)
      mrgManager <- crlService.buildResourceManager(mrgCoords.tenantId, mrgCoords.subscriptionId)
      petName = toManagedIdentityNameFromUser(user)
      region <- getRegionFromMrg(mrgCoords, mrgManager, samRequestContext)
      context = managedIdentityContext(mrgCoords, petName, region)
      azureUami <- traceIOWithContext("createUAMI", samRequestContext) { _ =>
        IO(
          // note that this will not fail when the UAMI already exists
          msiManager
            .identities()
            .define(petName.value)
            .withRegion(region)
            .withExistingResourceGroup(mrgCoords.managedResourceGroupName.value)
            .withTags(managedIdentityTags(user).asJava)
            .create(context)
        )
      }
      petToCreate = PetManagedIdentity(id, ManagedIdentityObjectId(azureUami.id()), ManagedIdentityDisplayName(azureUami.name()))
      createdPet <- directoryDAO.createPetManagedIdentity(petToCreate, samRequestContext)
    } yield (createdPet, true)

  def getOrCreateActionManagedIdentity(
      resource: FullyQualifiedResourceId,
      resourceAction: ResourceAction,
      billingProfileId: BillingProfileId,
      samRequestContext: SamRequestContext
  ): IO[(ActionManagedIdentity, Boolean)] = {
    val id = ActionManagedIdentityId(resource, resourceAction, billingProfileId)
    for {
      existingAmiOpt <- directoryDAO.loadActionManagedIdentity(id, samRequestContext)
      ami <- existingAmiOpt match {
        // pet exists in Sam DB - return it
        case Some(p) => IO.pure((p, false))
        // pet does not exist in Sam DB - create it
        case None => createActionManagedIdentity(id, samRequestContext)
      }
    } yield ami
  }

  def getActionManagedIdentity(
      resource: FullyQualifiedResourceId,
      resourceAction: ResourceAction,
      samRequestContext: SamRequestContext
  ): IO[Option[ActionManagedIdentity]] = directoryDAO.loadActionManagedIdentity(resource, resourceAction, samRequestContext)

  private def createActionManagedIdentity(
      id: ActionManagedIdentityId,
      samRequestContext: SamRequestContext
  ): IO[(ActionManagedIdentity, Boolean)] =
    for {
      mrgOpt <- azureManagedResourceGroupDAO.getManagedResourceGroupByBillingProfileId(id.billingProfileId, samRequestContext)
      mrg <- IO.fromOption(mrgOpt)(
        new WorkbenchExceptionWithErrorReport(
          ErrorReport(StatusCodes.NotFound, s"Managed Resource Group with Billing Profile ID [${id.billingProfileId}] does not exist")
        )
      )
      mrgCoordinates = mrg.managedResourceGroupCoordinates
      // mapping the result of the validate call to ensure that validation happens before anything is created in Azure
      msiManager <- crlService.buildMsiManager(mrgCoordinates.tenantId, mrgCoordinates.subscriptionId)
      mrgManager <- crlService.buildResourceManager(mrgCoordinates.tenantId, mrgCoordinates.subscriptionId)
      amiName = toManagedIdentityNameFromAmiId(id)
      region <- getRegionFromMrg(mrgCoordinates, mrgManager, samRequestContext)
      context = managedIdentityContext(mrgCoordinates, amiName, region)
      azureUami <- traceIOWithContext("createUAMI", samRequestContext) { _ =>
        IO(
          // note that this will not fail when the UAMI already exists
          msiManager
            .identities()
            .define(amiName.value)
            .withRegion(region)
            .withExistingResourceGroup(mrgCoordinates.managedResourceGroupName.value)
            .withTags(managedIdentityTags(id).asJava)
            .create(context)
        )
      }
      amiToCreate = ActionManagedIdentity(id, ManagedIdentityObjectId(azureUami.id()), ManagedIdentityDisplayName(azureUami.name()), mrgCoordinates)
      createdAmi <- directoryDAO.createActionManagedIdentity(amiToCreate, samRequestContext)
    } yield (createdAmi, true)

  def deleteActionManagedIdentity(id: ActionManagedIdentityId, samRequestContext: SamRequestContext): IO[Unit] =
    for {
      existing <- directoryDAO.loadActionManagedIdentity(id, samRequestContext)
      _ <- existing
        .map { ami =>
          for {
            msiManager <- crlService.buildMsiManager(ami.managedResourceGroupCoordinates.tenantId, ami.managedResourceGroupCoordinates.subscriptionId)
            _ <- IO(msiManager.identities().deleteById(ami.objectId.value))
            _ <- directoryDAO.deleteActionManagedIdentity(ami.id, samRequestContext)
          } yield {}
        }
        .getOrElse(IO.unit)
    } yield {}

  private def getRegionFromMrg(mrgCoords: ManagedResourceGroupCoordinates, mrgManager: ResourceManager, samRequestContext: SamRequestContext) =
    traceIOWithContext("getRegionFromMrg", samRequestContext) { _ =>
      IO(mrgManager.resourceGroups().getByName(mrgCoords.managedResourceGroupName.value).region())
    }

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
  def getBillingProfileId(request: GetOrCreatePetManagedIdentityRequest, samRequestContext: SamRequestContext): IO[Option[BillingProfileId]] =
    getBillingProfileIdFromSamDb(request, samRequestContext)

  private def getBillingProfileIdFromSamDb(request: GetOrCreatePetManagedIdentityRequest, samRequestContext: SamRequestContext): IO[Option[BillingProfileId]] =
    for {
      maybeMrg <- azureManagedResourceGroupDAO.getManagedResourceGroupByCoordinates(request.toManagedResourceGroupCoordinates, samRequestContext)
    } yield maybeMrg.map(_.billingProfileId)

  private def validateManagedResourceGroup(
      mrgCoords: ManagedResourceGroupCoordinates,
      samRequestContext: SamRequestContext,
      validateUser: Boolean = true
  ): IO[Unit] =
    for {
      _ <- IO.raiseWhen(config.azureServiceCatalog.isEmpty && config.azureMarketPlace.isEmpty)(
        new WorkbenchException("Either azure service catalog or azure market place must be configured")
      )
      _ <- config.azureServiceCatalog
        .map { serviceCatalog =>
          validateServiceCatalogManagedResourceGroup(serviceCatalog, mrgCoords, samRequestContext, validateUser)
        }
        .getOrElse(IO.unit)

      _ <- config.azureMarketPlace
        .map { marketPlace =>
          validateMarketPlaceManagedResourceGroup(marketPlace, mrgCoords, samRequestContext, validateUser)
        }
        .getOrElse(IO.unit)
    } yield ()

  /** Validates a managed resource group. Algorithm:
    *   1. Resolve the MRG in Azure 2. Get the managed app id from the MRG 3. Resolve the managed app 4. Get the managed app "plan" name and publisher 5.
    *      Validate the plan name and publisher matches a configured value 6. Validate that the caller is on the list of authorized users for the app
    */
  private def validateMarketPlaceManagedResourceGroup(
      marketPlace: AzureMarketPlace,
      mrgCoords: ManagedResourceGroupCoordinates,
      samRequestContext: SamRequestContext,
      validateUser: Boolean = true
  ): IO[ResourceGroup] =
    traceIOWithContext("validateManagedResourceGroup", samRequestContext) { _ =>
      for {
        resourceManager <- crlService.buildResourceManager(mrgCoords.tenantId, mrgCoords.subscriptionId)
        mrg <- lookupMrg(mrgCoords, resourceManager)
        appManager <- crlService.buildApplicationManager(mrgCoords.tenantId, mrgCoords.subscriptionId)
        appsInSubscription <- IO(appManager.applications().list().asScala.toSeq)
        managedApp <- IO.fromOption(appsInSubscription.find(_.managedResourceGroupId() == mrg.id()))(managedAppValidationFailure)
        plan <- validatePlan(managedApp, marketPlace.managedAppPlans)
        _ <- if (validateUser) validateAuthorizedAppUser(managedApp, plan.authorizedUserKey, samRequestContext) else IO.unit
      } yield mrg
    }

  /** Validates a managed resource group deployed from Azure Service Catalog. Service Catalog apps do not contain a "plan" or publisher. Algorithm:
    *   1. Resolve the MRG in Azure 2. Get the managed app id from the MRG 3. Resolve the managed app 4. Validate the app kind is "ServiceCatalog" 5. Validate
    *      that the caller is on the list of authorized users for the app
    */
  private def validateServiceCatalogManagedResourceGroup(
      serviceCatalog: AzureServiceCatalog,
      mrgCoords: ManagedResourceGroupCoordinates,
      samRequestContext: SamRequestContext,
      validateUser: Boolean = true
  ): IO[ResourceGroup] =
    traceIOWithContext("validateServiceCatalogManagedResourceGroup", samRequestContext) { _ =>
      for {
        resourceManager <- crlService.buildResourceManager(mrgCoords.tenantId, mrgCoords.subscriptionId)
        mrg <- lookupMrg(mrgCoords, resourceManager)
        appManager <- crlService.buildApplicationManager(mrgCoords.tenantId, mrgCoords.subscriptionId)
        appsInSubscription <- IO(appManager.applications().list().asScala)
        managedApp <- IO.fromOption(appsInSubscription.find(_.managedResourceGroupId() == mrg.id()))(managedAppValidationFailure)
        _ <-
          if (managedApp.kind() == serviceCatalog.managedAppTypeServiceCatalog && validateUser) {
            validateAuthorizedAppUser(
              managedApp,
              serviceCatalog.authorizedUserKey,
              samRequestContext
            )
          } else IO.unit
      } yield mrg
    }

  /** The users authorized to setup a managed application are stored as a comma separated list of email addresses in the parameters of the application. The
    * azure api is java so this code needs to deal with possible nulls and java Maps. Also the application parameters are untyped, fun.
    * @param app
    * @param authorizedUserKey
    * @param samRequestContext
    * @return
    */
  private def validateAuthorizedAppUser(app: Application, authorizedUserKey: String, samRequestContext: SamRequestContext): IO[Unit] = {
    val authorizedUsersValue = for {
      parametersObj <- Option(app.parameters()) if parametersObj.isInstanceOf[java.util.Map[_, _]]
      parametersMap = parametersObj.asInstanceOf[java.util.Map[_, _]]
      paramValuesObj <- Option(parametersMap.get(authorizedUserKey)) if paramValuesObj.isInstanceOf[java.util.Map[_, _]]
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
  private def getBillingProfileFromTag(mrg: ResourceGroup): Option[BillingProfileId] =
    mrg.tags().asScala.get(billingProfileTag).map(BillingProfileId(_))

  private def managedIdentityTags(user: SamUser): Map[String, String] =
    Map("samUserId" -> user.id.value, "samUserEmail" -> user.email.value)

  private def managedIdentityTags(amiId: ActionManagedIdentityId): Map[String, String] =
    Map("resourceTypeName" -> amiId.resourceId.resourceTypeName.value, "resourceId" -> amiId.resourceId.resourceId.value, "action" -> amiId.action.value)

  private def managedIdentityContext(mrgCoords: ManagedResourceGroupCoordinates, petName: ManagedIdentityDisplayName, region: Region): Context =
    Defaults.buildContext(
      CreateUserAssignedManagedIdentityRequestData
        .builder()
        .setTenantId(mrgCoords.tenantId.value)
        .setSubscriptionId(mrgCoords.subscriptionId.value)
        .setResourceGroupName(mrgCoords.managedResourceGroupName.value)
        .setName(petName.value)
        .setRegion(region)
        .build()
    )

  private def toManagedIdentityNameFromUser(user: SamUser): ManagedIdentityDisplayName =
    ManagedIdentityDisplayName(s"pet-${user.id.value}")

  def toManagedIdentityNameFromAmiId(amiId: ActionManagedIdentityId): ManagedIdentityDisplayName = {
    // Managed Identity Names are limited to 24 characters
    val actionPart = if (amiId.action.value.length > 11) amiId.action.value.substring(0, 11) else amiId.action.value
    val resourceIdPart =
      if (amiId.resourceId.resourceId.value.length > 12) amiId.resourceId.resourceId.value.substring(0, 12) else amiId.resourceId.resourceId.value
    ManagedIdentityDisplayName(s"$resourceIdPart-$actionPart")
  }

}
