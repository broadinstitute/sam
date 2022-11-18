package org.broadinstitute.dsde.workbench.sam.azure

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import com.azure.resourcemanager.managedapplications.models.Plan
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.{ConnectedTest, Generator}
import org.broadinstitute.dsde.workbench.sam.Generator.genWorkbenchUserAzure
import org.broadinstitute.dsde.workbench.sam.TestSupport._
import org.broadinstitute.dsde.workbench.sam.dataAccess.{MockAzureManagedResourceGroupDAO, MockDirectoryDAO, PostgresDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model.{ResourceId, UserStatus, UserStatusDetails}
import org.broadinstitute.dsde.workbench.sam.service.{NoExtensions, TosService, UserService}
import org.mockito.Mockito.when
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
    val azureService = new AzureService(crlService, directoryDAO, new MockAzureManagedResourceGroupDAO)

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
    val azureService = new AzureService(crlService, directoryDAO, new MockAzureManagedResourceGroupDAO)

    // build request
    val tenantId = TenantId(azureTestConfig.getString("tenantId"))
    val subscriptionId = SubscriptionId(azureTestConfig.getString("subscriptionId"))
    val managedResourceGroupName = ManagedResourceGroupName(azureTestConfig.getString("managedResourceGroupName"))
    val request = GetOrCreatePetManagedIdentityRequest(tenantId, subscriptionId, managedResourceGroupName)

    // call getBillingProfileId
    val res = azureService.getBillingProfileId(request).unsafeRunSync()

    // should return a billing profile id
    res shouldBe defined
    res.get shouldBe ResourceId("de38969d-f41b-4b80-99ba-db481e6db1cf")
  }

  "createManagedResourceGroup" should "create a managed resource group" in {
    val user = Generator.genWorkbenchUserAzure.sample
    val managedResourceGroup = Generator.genManagedResourceGroup.sample.get
    val mockMrgDAO = new MockAzureManagedResourceGroupDAO
    val svc =
      new AzureService(MockCrlService(user, managedResourceGroup.managedResourceGroupCoordinates.managedResourceGroupName), new MockDirectoryDAO(), mockMrgDAO)

    svc.createManagedResourceGroup(managedResourceGroup, samRequestContext.copy(samUser = user)).unsafeRunSync()
    mockMrgDAO.mrgs should contain(managedResourceGroup)
  }

  it should "create a managed resource group IN AZURE" taggedAs ConnectedTest in {
    val azureServicesConfig = appConfig.azureServicesConfig

    assume(azureServicesConfig.isDefined, "-- skipping Azure test")

    // create dependencies
    val crlService = new CrlService(azureServicesConfig.get)
    val mockMrgDAO = new MockAzureManagedResourceGroupDAO
    val azureService = new AzureService(crlService, new MockDirectoryDAO(), mockMrgDAO)

    // build request
    val azureTestConfig = config.getConfig("testStuff.azure")
    val managedResourceGroup = ManagedResourceGroup(
      ManagedResourceGroupCoordinates(
        TenantId(azureTestConfig.getString("tenantId")),
        SubscriptionId(azureTestConfig.getString("subscriptionId")),
        ManagedResourceGroupName(azureTestConfig.getString("managedResourceGroupName"))
      ),
      BillingProfileId("de38969d-f41b-4b80-99ba-db481e6db1cf")
    )

    val user = Generator.genWorkbenchUserAzure.sample.map(_.copy(email = WorkbenchEmail("rtitlefireclouddev@gmail.com")))

    azureService.createManagedResourceGroup(managedResourceGroup, samRequestContext.copy(samUser = user)).unsafeRunSync()
    mockMrgDAO.mrgs should contain(managedResourceGroup)
  }

  it should "Conflict when MRG coordinates already exist" in {
    val user = Generator.genWorkbenchUserAzure.sample
    val managedResourceGroup = Generator.genManagedResourceGroup.sample.get
    val mockMrgDAO = new MockAzureManagedResourceGroupDAO
    val svc =
      new AzureService(MockCrlService(user, managedResourceGroup.managedResourceGroupCoordinates.managedResourceGroupName), new MockDirectoryDAO(), mockMrgDAO)

    mockMrgDAO.insertManagedResourceGroup(managedResourceGroup.copy(billingProfileId = BillingProfileId("no the same")), samRequestContext).unsafeRunSync()
    val err = intercept[WorkbenchExceptionWithErrorReport] {
      svc.createManagedResourceGroup(managedResourceGroup, samRequestContext.copy(samUser = user)).unsafeRunSync()
    }
    err.errorReport.statusCode shouldBe Some(StatusCodes.Conflict)
    mockMrgDAO.mrgs should not contain managedResourceGroup
  }

  it should "Conflict when MRG billing project already exist" in {
    val user = Generator.genWorkbenchUserAzure.sample
    val managedResourceGroup = Generator.genManagedResourceGroup.sample.get
    val mockMrgDAO = new MockAzureManagedResourceGroupDAO
    val svc =
      new AzureService(MockCrlService(user, managedResourceGroup.managedResourceGroupCoordinates.managedResourceGroupName), new MockDirectoryDAO(), mockMrgDAO)

    mockMrgDAO
      .insertManagedResourceGroup(
        managedResourceGroup
          .copy(managedResourceGroupCoordinates = managedResourceGroup.managedResourceGroupCoordinates.copy(tenantId = TenantId("other tenant"))),
        samRequestContext
      )
      .unsafeRunSync()
    val err = intercept[WorkbenchExceptionWithErrorReport] {
      svc.createManagedResourceGroup(managedResourceGroup, samRequestContext.copy(samUser = user)).unsafeRunSync()
    }
    err.errorReport.statusCode shouldBe Some(StatusCodes.Conflict)
    mockMrgDAO.mrgs should not contain managedResourceGroup
  }

  it should "Forbidden when MRG does not exist in Azure" in {
    val user = Generator.genWorkbenchUserAzure.sample
    val managedResourceGroup = Generator.genManagedResourceGroup.sample.get
    val mockMrgDAO = new MockAzureManagedResourceGroupDAO
    val svc = new AzureService(MockCrlService(user), new MockDirectoryDAO(), mockMrgDAO)

    val err = intercept[WorkbenchExceptionWithErrorReport] {
      svc.createManagedResourceGroup(managedResourceGroup, samRequestContext.copy(samUser = user)).unsafeRunSync()
    }
    err.errorReport.statusCode shouldBe Some(StatusCodes.Forbidden)
    mockMrgDAO.mrgs should not contain managedResourceGroup
  }

  it should "Forbidden when MRG is not associated to an Application" in {
    val user = Generator.genWorkbenchUserAzure.sample
    val managedResourceGroup = Generator.genManagedResourceGroup.sample.get
    val mockMrgDAO = new MockAzureManagedResourceGroupDAO
    val crlService = MockCrlService(user, managedResourceGroup.managedResourceGroupCoordinates.managedResourceGroupName)
    val svc = new AzureService(crlService, new MockDirectoryDAO(), mockMrgDAO)

    val mockApplication = crlService
      .buildApplicationManager(
        managedResourceGroup.managedResourceGroupCoordinates.tenantId,
        managedResourceGroup.managedResourceGroupCoordinates.subscriptionId
      )
      .unsafeRunSync()
      .applications()
      .list()
      .asScala
      .head
    when(mockApplication.managedResourceGroupId()).thenReturn("something else")

    val err = intercept[WorkbenchExceptionWithErrorReport] {
      svc.createManagedResourceGroup(managedResourceGroup, samRequestContext.copy(samUser = user)).unsafeRunSync()
    }
    err.errorReport.statusCode shouldBe Some(StatusCodes.Forbidden)
    mockMrgDAO.mrgs should not contain managedResourceGroup
  }

  it should "Forbidden when an Application does not have an accepted Plan name" in {
    val user = Generator.genWorkbenchUserAzure.sample
    val managedResourceGroup = Generator.genManagedResourceGroup.sample.get
    val mockMrgDAO = new MockAzureManagedResourceGroupDAO
    val crlService = MockCrlService(user, managedResourceGroup.managedResourceGroupCoordinates.managedResourceGroupName)
    val svc = new AzureService(crlService, new MockDirectoryDAO(), mockMrgDAO)

    val mockApplication = crlService
      .buildApplicationManager(
        managedResourceGroup.managedResourceGroupCoordinates.tenantId,
        managedResourceGroup.managedResourceGroupCoordinates.subscriptionId
      )
      .unsafeRunSync()
      .applications()
      .list()
      .asScala
      .head
    when(mockApplication.plan())
      .thenReturn(new Plan().withName(MockCrlService.defaultManagedAppPlan.name).withPublisher("other publisher"))

    val err = intercept[WorkbenchExceptionWithErrorReport] {
      svc.createManagedResourceGroup(managedResourceGroup, samRequestContext.copy(samUser = user)).unsafeRunSync()
    }
    err.errorReport.statusCode shouldBe Some(StatusCodes.Forbidden)
    mockMrgDAO.mrgs should not contain managedResourceGroup
  }

  it should "Forbidden when an Application does not have an accepted Plan publisher" in {
    val user = Generator.genWorkbenchUserAzure.sample
    val managedResourceGroup = Generator.genManagedResourceGroup.sample.get
    val mockMrgDAO = new MockAzureManagedResourceGroupDAO
    val crlService = MockCrlService(user, managedResourceGroup.managedResourceGroupCoordinates.managedResourceGroupName)
    val svc = new AzureService(crlService, new MockDirectoryDAO(), mockMrgDAO)

    val mockApplication = crlService
      .buildApplicationManager(
        managedResourceGroup.managedResourceGroupCoordinates.tenantId,
        managedResourceGroup.managedResourceGroupCoordinates.subscriptionId
      )
      .unsafeRunSync()
      .applications()
      .list()
      .asScala
      .head
    when(mockApplication.plan())
      .thenReturn(new Plan().withName("other name").withPublisher(MockCrlService.defaultManagedAppPlan.publisher))

    val err = intercept[WorkbenchExceptionWithErrorReport] {
      svc.createManagedResourceGroup(managedResourceGroup, samRequestContext.copy(samUser = user)).unsafeRunSync()
    }
    err.errorReport.statusCode shouldBe Some(StatusCodes.Forbidden)
    mockMrgDAO.mrgs should not contain managedResourceGroup
  }

  it should "Forbidden when user is not authorized for Application" in {
    val user = Generator.genWorkbenchUserAzure.sample
    val managedResourceGroup = Generator.genManagedResourceGroup.sample.get
    val mockMrgDAO = new MockAzureManagedResourceGroupDAO
    val svc =
      new AzureService(MockCrlService(user, managedResourceGroup.managedResourceGroupCoordinates.managedResourceGroupName), new MockDirectoryDAO(), mockMrgDAO)

    val err = intercept[WorkbenchExceptionWithErrorReport] {
      svc
        .createManagedResourceGroup(managedResourceGroup, samRequestContext.copy(samUser = user.map(_.copy(email = WorkbenchEmail("other@email.com")))))
        .unsafeRunSync()
    }
    err.errorReport.statusCode shouldBe Some(StatusCodes.Forbidden)
    mockMrgDAO.mrgs should not contain managedResourceGroup
  }
}
