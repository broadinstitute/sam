package org.broadinstitute.dsde.workbench.sam.azure

import cats.effect.IO
import org.broadinstitute.dsde.workbench.sam.ConnectedTest
import org.broadinstitute.dsde.workbench.sam.Generator.genWorkbenchUserAzure
import org.broadinstitute.dsde.workbench.sam.TestSupport._
import org.broadinstitute.dsde.workbench.sam.dataAccess.PostgresDirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.{ResourceId, UserStatus, UserStatusDetails}
import org.broadinstitute.dsde.workbench.sam.service.{NoExtensions, TosService, UserService}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

class AzureServiceSpec extends AnyFlatSpec with Matchers with ScalaFutures {
  implicit val ec = scala.concurrent.ExecutionContext.global
  implicit val ioRuntime = cats.effect.unsafe.IORuntime.global

  "AzureService" should "create a pet managed identity" taggedAs ConnectedTest in {
    val azureServicesConfig = appConfig.azureServicesConfig

    assume(azureServicesConfig.isDefined, "-- skipping Azure test")

    // create dependencies
    val directoryDAO = new PostgresDirectoryDAO(dbRef, dbRef)
    val crlService = new CrlService(azureServicesConfig.get)
    val tosService = new TosService(directoryDAO, googleServicesConfig.appsDomain, tosConfig)
    val userService = new UserService(directoryDAO, NoExtensions, Seq.empty, tosService)
    val azureTestConfig = config.getConfig("testStuff.azure")
    val azureService = new AzureService(crlService, directoryDAO)

    // create user
    val defaultUser = genWorkbenchUserAzure.sample.get
    val userStatus = IO.fromFuture(IO(userService.createUser(defaultUser, samRequestContext))).unsafeRunSync()
    userStatus shouldBe UserStatus(UserStatusDetails(defaultUser.id, defaultUser.email), Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true))

    // user should exist in postgres
    directoryDAO.loadUser(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultUser.copy(enabled = true))

    // pet managed identity should not exist in postgres
    val tenantId = TenantId(azureTestConfig.getString("tenantId"))
    val subscriptionId = SubscriptionId(azureTestConfig.getString("subscriptionId"))
    val managedResourceGroupName = ManagedResourceGroupName(azureTestConfig.getString("managedResourceGroupName"))
    val petManagedIdentityId = PetManagedIdentityId(defaultUser.id, tenantId, subscriptionId, managedResourceGroupName)
    directoryDAO.loadPetManagedIdentity(petManagedIdentityId, samRequestContext).unsafeRunSync() shouldBe None

    // managed identity should not exist in Azure
    val msiManager = crlService.buildMsiManager(tenantId, subscriptionId).unsafeRunSync()
    msiManager.identities().listByResourceGroup(managedResourceGroupName.value).asScala.toList.exists { i =>
      i.name() === s"pet-${defaultUser.id.value}"
    } shouldBe false

    // create pet for user
    val request = GetOrCreatePetManagedIdentityRequest(tenantId, subscriptionId, managedResourceGroupName)
    val (res, created) = azureService.getOrCreateUserPetManagedIdentity(defaultUser, request, samRequestContext).unsafeRunSync()
    created shouldBe true
    res.id shouldBe petManagedIdentityId
    res.displayName shouldBe ManagedIdentityDisplayName(s"pet-${defaultUser.id.value}")

    // pet managed identity should now exist in postgres
    directoryDAO.loadPetManagedIdentity(petManagedIdentityId, samRequestContext).unsafeRunSync() shouldBe Some(res)

    // managed identity should now exist in azure
    val azureRes = msiManager.identities().getById(res.objectId.value)
    azureRes should not be null
    azureRes.tenantId() shouldBe tenantId.value
    azureRes.resourceGroupName() shouldBe managedResourceGroupName.value
    azureRes.id() shouldBe res.objectId.value
    azureRes.name() shouldBe res.displayName.value

    // call getOrCreate again
    val (res2, created2) = azureService.getOrCreateUserPetManagedIdentity(defaultUser, request, samRequestContext).unsafeRunSync()
    created2 shouldBe false
    res2 shouldBe res

    // pet should still exist in postgres and azure
    directoryDAO.loadPetManagedIdentity(petManagedIdentityId, samRequestContext).unsafeRunSync() shouldBe Some(res2)
    val azureRes2 = msiManager.identities().getById(res.objectId.value)
    azureRes2 should not be null
    azureRes2.tenantId() shouldBe tenantId.value
    azureRes2.resourceGroupName() shouldBe managedResourceGroupName.value
    azureRes2.id() shouldBe res2.objectId.value
    azureRes2.name() shouldBe res2.displayName.value

    // delete managed identity from Azure
    // this is a best effort -- it will be deleted anyway by Janitor
    msiManager.identities().deleteById(azureRes.id())
  }

  it should "get the billing profile id from the managed resource group" taggedAs ConnectedTest in {
    val azureServicesConfig = appConfig.azureServicesConfig

    assume(azureServicesConfig.isDefined, "-- skipping Azure test")

    // create dependencies
    val directoryDAO = new PostgresDirectoryDAO(dbRef, dbRef)
    val azureTestConfig = config.getConfig("testStuff.azure")
    val crlService = new CrlService(azureServicesConfig.get)
    val azureService = new AzureService(crlService, directoryDAO)

    // build request
    val tenantId = TenantId(azureTestConfig.getString("tenantId"))
    val subscriptionId = SubscriptionId(azureTestConfig.getString("subscriptionId"))
    val managedResourceGroupName = ManagedResourceGroupName(azureTestConfig.getString("managedResourceGroupName"))
    val request = GetOrCreatePetManagedIdentityRequest(tenantId, subscriptionId, managedResourceGroupName)

    // call getBillingProfileId
    val res = azureService.getBillingProfileId(request).unsafeRunSync()

    // should return a billing profile id
    res shouldBe defined
    res.get shouldBe ResourceId("wm-default-spend-profile-id")
  }
}
