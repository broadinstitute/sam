package org.broadinstitute.dsde.workbench.sam.azure

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.testkit.TestKit
import com.azure.resourcemanager.managedapplications.models.Plan
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.Generator.genWorkbenchUserAzure
import org.broadinstitute.dsde.workbench.sam.TestSupport._
import org.broadinstitute.dsde.workbench.sam.api.TestSamRoutes.SamResourceActionPatterns
import org.broadinstitute.dsde.workbench.sam.config.AzureServicesConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.{
  AccessPolicyDAO,
  DirectoryDAO,
  MockAzureManagedResourceGroupDAO,
  MockDirectoryDAO,
  PostgresAccessPolicyDAO,
  PostgresAzureManagedResourceGroupDAO,
  PostgresDirectoryDAO
}
import org.broadinstitute.dsde.workbench.sam.model.{
  FullyQualifiedResourceId,
  ResourceAction,
  ResourceActionPattern,
  ResourceId,
  ResourceRole,
  ResourceRoleName,
  ResourceType,
  ResourceTypeName,
  UserStatus,
  UserStatusDetails
}
import org.broadinstitute.dsde.workbench.sam.service.{NoExtensions, PolicyEvaluatorService, ResourceService, TosService, UserService}
import org.broadinstitute.dsde.workbench.sam.{ConnectedTest, Generator, TestSupport}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import scala.jdk.CollectionConverters._

class AzureServiceSpec(_system: ActorSystem)
    extends TestKit(_system)
    with AnyFlatSpecLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with MockitoSugar {
  implicit val ec = scala.concurrent.ExecutionContext.global
  implicit val ioRuntime = cats.effect.unsafe.IORuntime.global

  def this() = this(ActorSystem("AzureServiceSpec"))

  override def beforeAll(): Unit =
    super.beforeAll()

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  override def beforeEach(): Unit =
    TestSupport.truncateAll

  "AzureService" should "create a pet managed identity" taggedAs ConnectedTest in {
    val azureServicesConfig = appConfig.azureServicesConfig
    val janitorConfig = appConfig.janitorConfig

    assume(azureServicesConfig.isDefined && janitorConfig.enabled, "-- skipping Azure test")

    // create dependencies
    val directoryDAO = new PostgresDirectoryDAO(dbRef, dbRef)
    val crlService = new CrlService(azureServicesConfig.get, janitorConfig)
    val tosService = new TosService(NoExtensions, directoryDAO, tosConfig)
    val userService = new UserService(directoryDAO, NoExtensions, Seq.empty, tosService)
    val azureTestConfig = config.getConfig("testStuff.azure")
    setUpResources(directoryDAO)
    val azureService = new AzureService(azureServicesConfig.get, crlService, directoryDAO, new MockAzureManagedResourceGroupDAO)

    // create user
    val defaultUser = genWorkbenchUserAzure.sample.get
    val userStatus = userService.createUser(defaultUser, samRequestContext).unsafeRunSync()
    userStatus shouldBe UserStatus(
      UserStatusDetails(defaultUser.id, defaultUser.email),
      Map("tosAccepted" -> false, "adminEnabled" -> true, "ldap" -> true, "allUsersGroup" -> true, "google" -> true)
    )

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
  def setUpResources(directoryDAO: DirectoryDAO): (ResourceService, ResourceType, ResourceAction) = {
    lazy val policyDAO: AccessPolicyDAO = new PostgresAccessPolicyDAO(TestSupport.dbRef, TestSupport.dbRef)
    val emailDomain = "example.com"
    val ownerRoleName = ResourceRoleName("owner")
    val viewAction = ResourceAction("view")

    val defaultResourceTypeActions =
      Set(ResourceAction("alter_policies"), ResourceAction("delete"), ResourceAction("read_policies"), viewAction, ResourceAction("non_owner_action"))
    val defaultResourceTypeActionPatterns = Set(
      SamResourceActionPatterns.alterPolicies,
      SamResourceActionPatterns.delete,
      SamResourceActionPatterns.readPolicies,
      ResourceActionPattern("view", "", false),
      ResourceActionPattern("non_owner_action", "", false)
    )
    val defaultResourceType = ResourceType(
      ResourceTypeName(UUID.randomUUID().toString),
      defaultResourceTypeActionPatterns,
      Set(
        ResourceRole(ownerRoleName, defaultResourceTypeActions - ResourceAction("non_owner_action")),
        ResourceRole(ResourceRoleName("other"), Set(ResourceAction("view"), ResourceAction("non_owner_action")))
      ),
      ownerRoleName
    )

    val resourceTypes = Map(
      defaultResourceType.name -> defaultResourceType
    )
    val policyEvaluatorService = PolicyEvaluatorService(emailDomain, resourceTypes, policyDAO, directoryDAO)
    val resourceService =
      new ResourceService(resourceTypes, policyEvaluatorService, policyDAO, directoryDAO, NoExtensions, emailDomain, Set("test.firecloud.org"))
    (resourceService, defaultResourceType, viewAction)
  }

  it should "create and delete an action managed identity" taggedAs ConnectedTest in {
    val azureServicesConfig = appConfig.azureServicesConfig
    val janitorConfig = appConfig.janitorConfig

    assume(azureServicesConfig.isDefined && janitorConfig.enabled, "-- skipping Azure test")

    // create dependencies
    val directoryDAO = new PostgresDirectoryDAO(dbRef, dbRef)
    val crlService = new CrlService(azureServicesConfig.get, janitorConfig)
    val tosService = new TosService(NoExtensions, directoryDAO, tosConfig)
    val userService = new UserService(directoryDAO, NoExtensions, Seq.empty, tosService)
    val azureManagedResourceGroupDAO = new PostgresAzureManagedResourceGroupDAO(TestSupport.dbRef, TestSupport.dbRef)
    val azureTestConfig = config.getConfig("testStuff.azure")
    val (resourceService, defaultResourceType, viewAction) = setUpResources(directoryDAO)
    val azureService = new AzureService(azureServicesConfig.get, crlService, directoryDAO, azureManagedResourceGroupDAO)

    // create user
    val defaultUser = Generator.genWorkbenchUserAzure.sample.map(_.copy(email = WorkbenchEmail("hermione.owner@test.firecloud.org"))).get
    val userStatus = userService.createUser(defaultUser, samRequestContext).unsafeRunSync()
    userStatus shouldBe UserStatus(
      UserStatusDetails(defaultUser.id, defaultUser.email),
      Map("tosAccepted" -> false, "adminEnabled" -> true, "ldap" -> true, "allUsersGroup" -> true, "google" -> true)
    )

    // user should exist in postgres
    directoryDAO.loadUser(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe Some(defaultUser.copy(enabled = true))

    // Create the resource type and resource
    val resourceName = ResourceId("resource")
    val resource = FullyQualifiedResourceId(defaultResourceType.name, resourceName)
    resourceService.createResourceType(defaultResourceType, samRequestContext).unsafeRunSync()
    runAndWait(resourceService.createResource(defaultResourceType, resourceName, defaultUser, samRequestContext))

    // Create the "billing profile" resource. There's no actual need for it to be a "billing profile", we just need a resource to attach the managed resource group to.
    val billingProfileId = BillingProfileId("de38969d-f41b-4b80-99ba-db481e6db1cf")
    val billingProfileResource = runAndWait(resourceService.createResource(defaultResourceType, billingProfileId.asResourceId, defaultUser, samRequestContext))

    // action managed identity should not exist in postgres
    val tenantId = TenantId(azureTestConfig.getString("tenantId"))
    val subscriptionId = SubscriptionId(azureTestConfig.getString("subscriptionId"))
    val managedResourceGroupName = ManagedResourceGroupName(azureTestConfig.getString("managedResourceGroupName"))
    val mrgCoordinates = ManagedResourceGroupCoordinates(tenantId, subscriptionId, managedResourceGroupName)
    val managedResourceGroup = ManagedResourceGroup(mrgCoordinates, billingProfileId)
    runAndWait(azureService.createManagedResourceGroup(managedResourceGroup, samRequestContext.copy(samUser = Some(defaultUser))))

    val actionManagedIdentityId =
      ActionManagedIdentityId(resource, viewAction, billingProfileId)
    directoryDAO.loadActionManagedIdentity(actionManagedIdentityId, samRequestContext).unsafeRunSync() shouldBe None

    // managed identity should not exist in Azure
    val msiManager = crlService.buildMsiManager(tenantId, subscriptionId).unsafeRunSync()
    msiManager.identities().listByResourceGroup(managedResourceGroupName.value).asScala.toList.exists { i =>
      i.name() === azureService.toManagedIdentityNameFromAmiId(actionManagedIdentityId)
    } shouldBe false

    // create action managed identity
    val (res, created) = azureService
      .getOrCreateActionManagedIdentity(resource, viewAction, billingProfileId, samRequestContext.copy(samUser = Some(defaultUser)))
      .unsafeRunSync()
    created shouldBe true
    res.id shouldBe actionManagedIdentityId
    res.displayName shouldBe ManagedIdentityDisplayName(s"${resource.resourceId.value}-${viewAction.value}")

    // action managed identity should now exist in postgres
    directoryDAO.loadActionManagedIdentity(actionManagedIdentityId, samRequestContext).unsafeRunSync() shouldBe Some(res)

    // managed identity should now exist in azure
    val azureRes = msiManager.identities().getById(res.objectId.value)
    azureRes should not be null
    azureRes.tenantId() shouldBe tenantId.value
    azureRes.resourceGroupName() shouldBe managedResourceGroupName.value
    azureRes.id() shouldBe res.objectId.value
    azureRes.name() shouldBe res.displayName.value

    // call getOrCreate again
    val (res2, created2) = azureService.getOrCreateActionManagedIdentity(resource, viewAction, billingProfileId, samRequestContext).unsafeRunSync()
    created2 shouldBe false
    res2 shouldBe res

    // pet should still exist in postgres and azure
    directoryDAO.loadActionManagedIdentity(actionManagedIdentityId, samRequestContext).unsafeRunSync() shouldBe Some(res2)
    val azureRes2 = msiManager.identities().getById(res.objectId.value)
    azureRes2 should not be null
    azureRes2.tenantId() shouldBe tenantId.value
    azureRes2.resourceGroupName() shouldBe managedResourceGroupName.value
    azureRes2.id() shouldBe res2.objectId.value
    azureRes2.name() shouldBe res2.displayName.value

    // delete action managed identity
    azureService.deleteActionManagedIdentity(actionManagedIdentityId, samRequestContext).unsafeRunSync()

    // action managed identity should not exist in postgres
    directoryDAO.loadActionManagedIdentity(actionManagedIdentityId, samRequestContext).unsafeRunSync() shouldBe None

    // managed identity should not exist in Azure
    msiManager.identities().listByResourceGroup(managedResourceGroupName.value).asScala.toList.exists { i =>
      i.name() === azureService.toManagedIdentityNameFromAmiId(actionManagedIdentityId)
    } shouldBe false

    // delete managed identity from Azure
    // this is a best effort -- it will be deleted anyway by Janitor
    msiManager.identities().deleteById(azureRes.id())
  }

  "createManagedResourceGroup" should "create a managed resource group" in {
    val user = Generator.genWorkbenchUserAzure.sample
    val managedResourceGroup = Generator.genManagedResourceGroup.sample.get
    val mockMrgDAO = new MockAzureManagedResourceGroupDAO
    val azureServicesConfig = appConfig.azureServicesConfig
    val mockCrlService = MockCrlService(user, managedResourceGroup.managedResourceGroupCoordinates.managedResourceGroupName)
    val mockAzureServicesConfig = mock[AzureServicesConfig]

    val svc =
      new AzureService(
        mockAzureServicesConfig,
        mockCrlService,
        new MockDirectoryDAO(),
        mockMrgDAO
      )

    when(mockAzureServicesConfig.azureServiceCatalogAppsEnabled)
      .thenReturn(false)

    when(mockCrlService.getManagedAppPlans)
      .thenReturn(Seq(MockCrlService.defaultManagedAppPlan))

    svc.createManagedResourceGroup(managedResourceGroup, samRequestContext.copy(samUser = user)).unsafeRunSync()
    mockMrgDAO.mrgs should contain(managedResourceGroup)
  }

  it should "create a managed resource group IN AZURE" taggedAs ConnectedTest in {
    val azureServicesConfig = appConfig.azureServicesConfig
    val janitorConfig = appConfig.janitorConfig

    assume(azureServicesConfig.isDefined && janitorConfig.enabled, "-- skipping Azure test")

    // create dependencies
    val crlService = new CrlService(azureServicesConfig.get, janitorConfig)
    val mockMrgDAO = new MockAzureManagedResourceGroupDAO
    val azureService = new AzureService(azureServicesConfig.get, crlService, new MockDirectoryDAO(), mockMrgDAO)

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

    val user = Generator.genWorkbenchUserAzure.sample.map(_.copy(email = WorkbenchEmail("hermione.owner@test.firecloud.org")))

    azureService.createManagedResourceGroup(managedResourceGroup, samRequestContext.copy(samUser = user)).unsafeRunSync()
    mockMrgDAO.mrgs should contain(managedResourceGroup)
  }

  it should "Conflict when MRG coordinates already exist" in {
    val user = Generator.genWorkbenchUserAzure.sample
    val managedResourceGroup = Generator.genManagedResourceGroup.sample.get
    val mockMrgDAO = new MockAzureManagedResourceGroupDAO
    val mockAzureServicesConfig = mock[AzureServicesConfig]

    val svc =
      new AzureService(
        mockAzureServicesConfig,
        MockCrlService(user, managedResourceGroup.managedResourceGroupCoordinates.managedResourceGroupName),
        new MockDirectoryDAO(),
        mockMrgDAO
      )

    when(mockAzureServicesConfig.azureServiceCatalogAppsEnabled)
      .thenReturn(false)

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
    val mockCrlService = MockCrlService(user, managedResourceGroup.managedResourceGroupCoordinates.managedResourceGroupName)
    val mockAzureServicesConfig = mock[AzureServicesConfig]

    val svc =
      new AzureService(
        mockAzureServicesConfig,
        mockCrlService,
        new MockDirectoryDAO(),
        mockMrgDAO
      )

    when(mockAzureServicesConfig.azureServiceCatalogAppsEnabled)
      .thenReturn(false)

    when(mockCrlService.getManagedAppPlans)
      .thenReturn(Seq(MockCrlService.defaultManagedAppPlan))

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
    val mockAzureServicesConfig = mock[AzureServicesConfig]
    val svc = new AzureService(mockAzureServicesConfig, MockCrlService(user), new MockDirectoryDAO(), mockMrgDAO)

    when(mockAzureServicesConfig.azureServiceCatalogAppsEnabled)
      .thenReturn(false)

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
    val mockAzureServicesConfig = mock[AzureServicesConfig]

    val svc = new AzureService(mockAzureServicesConfig, crlService, new MockDirectoryDAO(), mockMrgDAO)

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

    when(mockAzureServicesConfig.azureServiceCatalogAppsEnabled)
      .thenReturn(false)

    val err = intercept[WorkbenchExceptionWithErrorReport] {
      svc.createManagedResourceGroup(managedResourceGroup, samRequestContext.copy(samUser = user)).unsafeRunSync()
    }
    err.errorReport.statusCode shouldBe Some(StatusCodes.Forbidden)
    mockMrgDAO.mrgs should not contain managedResourceGroup
  }

  it should "Forbidden when an Application has a null Plan" in {
    val user = Generator.genWorkbenchUserAzure.sample
    val managedResourceGroup = Generator.genManagedResourceGroup.sample.get
    val mockMrgDAO = new MockAzureManagedResourceGroupDAO
    val crlService = MockCrlService(user, managedResourceGroup.managedResourceGroupCoordinates.managedResourceGroupName)
    val mockAzureServicesConfig = mock[AzureServicesConfig]

    val svc = new AzureService(mockAzureServicesConfig, crlService, new MockDirectoryDAO(), mockMrgDAO)

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
      .thenReturn(null)

    when(mockAzureServicesConfig.azureServiceCatalogAppsEnabled)
      .thenReturn(false)

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
    val mockAzureServicesConfig = mock[AzureServicesConfig]
    val svc = new AzureService(mockAzureServicesConfig, crlService, new MockDirectoryDAO(), mockMrgDAO)

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

    when(mockAzureServicesConfig.azureServiceCatalogAppsEnabled)
      .thenReturn(false)

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
    val mockAzureServicesConfig = mock[AzureServicesConfig]
    val svc = new AzureService(mockAzureServicesConfig, crlService, new MockDirectoryDAO(), mockMrgDAO)

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

    when(mockAzureServicesConfig.azureServiceCatalogAppsEnabled)
      .thenReturn(false)

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
    val mockAzureServicesConfig = mock[AzureServicesConfig]

    val svc =
      new AzureService(
        mockAzureServicesConfig,
        MockCrlService(user, managedResourceGroup.managedResourceGroupCoordinates.managedResourceGroupName),
        new MockDirectoryDAO(),
        mockMrgDAO
      )

    when(mockAzureServicesConfig.azureServiceCatalogAppsEnabled)
      .thenReturn(false)

    val err = intercept[WorkbenchExceptionWithErrorReport] {
      svc
        .createManagedResourceGroup(managedResourceGroup, samRequestContext.copy(samUser = user.map(_.copy(email = WorkbenchEmail("other@email.com")))))
        .unsafeRunSync()
    }
    err.errorReport.statusCode shouldBe Some(StatusCodes.Forbidden)
    mockMrgDAO.mrgs should not contain managedResourceGroup
  }

  it should "Forbidden when an Application has invalid parameters" in {
    val user = Generator.genWorkbenchUserAzure.sample
    val managedResourceGroup = Generator.genManagedResourceGroup.sample.get
    val mockMrgDAO = new MockAzureManagedResourceGroupDAO
    val crlService = MockCrlService(user, managedResourceGroup.managedResourceGroupCoordinates.managedResourceGroupName)
    val mockAzureServicesConfig = mock[AzureServicesConfig]

    val svc = new AzureService(mockAzureServicesConfig, crlService, new MockDirectoryDAO(), mockMrgDAO)

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

    val invalidParameters = List(
      null,
      Map.empty.asJava,
      "I am a not a map",
      Map("value" -> "I am not a map").asJava,
      Map("value" -> Map.empty.asJava).asJava,
      Map("value" -> Map(MockCrlService.defaultManagedAppPlan.authorizedUserKey -> 5).asJava).asJava
    )
    invalidParameters.foreach { parameters =>
      when(mockApplication.parameters())
        .thenReturn(parameters)

      when(mockAzureServicesConfig.azureServiceCatalogAppsEnabled)
        .thenReturn(false)

      val err = intercept[WorkbenchExceptionWithErrorReport] {
        svc.createManagedResourceGroup(managedResourceGroup, samRequestContext.copy(samUser = user)).unsafeRunSync()
      }
      err.errorReport.statusCode shouldBe Some(StatusCodes.Forbidden)
      mockMrgDAO.mrgs should not contain managedResourceGroup
    }
  }

  "deleteManagedResourceGroup" should "delete a managed resource group" in {
    val user = Generator.genWorkbenchUserAzure.sample
    val managedResourceGroup = Generator.genManagedResourceGroup.sample.get
    val mockMrgDAO = new MockAzureManagedResourceGroupDAO
    val mockAzureServicesConfig = mock[AzureServicesConfig]
    val svc =
      new AzureService(
        mockAzureServicesConfig,
        MockCrlService(user, managedResourceGroup.managedResourceGroupCoordinates.managedResourceGroupName),
        new MockDirectoryDAO(),
        mockMrgDAO
      )

    mockMrgDAO.insertManagedResourceGroup(managedResourceGroup, samRequestContext).unsafeRunSync()
    svc.deleteManagedResourceGroup(managedResourceGroup.billingProfileId, samRequestContext.copy(samUser = user)).unsafeRunSync()
    mockMrgDAO.mrgs shouldNot contain(managedResourceGroup)
  }

  it should "NotFound when MRG does not exist" in {
    val user = Generator.genWorkbenchUserAzure.sample
    val mockMrgDAO = new MockAzureManagedResourceGroupDAO
    val managedResourceGroup = Generator.genManagedResourceGroup.sample.get
    val mockAzureServicesConfig = mock[AzureServicesConfig]

    val svc =
      new AzureService(
        mockAzureServicesConfig,
        MockCrlService(user, managedResourceGroup.managedResourceGroupCoordinates.managedResourceGroupName),
        new MockDirectoryDAO(),
        mockMrgDAO
      )

    val err = intercept[WorkbenchExceptionWithErrorReport] {
      svc.deleteManagedResourceGroup(managedResourceGroup.billingProfileId, samRequestContext.copy(samUser = user)).unsafeRunSync()
    }
    err.errorReport.statusCode shouldBe Some(StatusCodes.NotFound)
  }
}
