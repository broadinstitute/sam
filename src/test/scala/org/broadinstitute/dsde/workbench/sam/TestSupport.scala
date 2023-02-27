package org.broadinstitute.dsde.workbench.sam

import akka.actor.ActorSystem
import akka.stream.Materializer
import cats.effect._
import cats.effect.unsafe.implicits.global
import cats.kernel.Eq
import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.dataaccess.PubSubNotificationDAO
import org.broadinstitute.dsde.workbench.google.mock._
import org.broadinstitute.dsde.workbench.google.{GoogleDirectoryDAO, GoogleIamDAO}
import org.broadinstitute.dsde.workbench.google2.mock.FakeGoogleStorageInterpreter
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.oauth2.OpenIDConnectConfiguration
import org.broadinstitute.dsde.workbench.oauth2.mock.FakeOpenIDConnectConfiguration
import org.broadinstitute.dsde.workbench.openTelemetry.{FakeOpenTelemetryMetricsInterpreter, OpenTelemetryMetrics, OpenTelemetryMetricsInterpreter}
import org.broadinstitute.dsde.workbench.sam.api._
import org.broadinstitute.dsde.workbench.sam.azure.{AzureService, MockCrlService}
import org.broadinstitute.dsde.workbench.sam.config.AppConfig._
import org.broadinstitute.dsde.workbench.sam.config._
import org.broadinstitute.dsde.workbench.sam.dataAccess._
import org.broadinstitute.dsde.workbench.sam.db.TestDbReference
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.google.{GoogleExtensionRoutes, GoogleExtensions, GoogleGroupSynchronizer, GoogleKeyCache}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.scalatest.Tag
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.Configuration
import org.scalatest.time.{Seconds, Span}
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalikejdbc.QueryDSL.delete
import scalikejdbc.withSQL

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext.Implicits.{global => globalEc}
import scala.concurrent.duration._
import scala.concurrent.{Await, Awaitable, ExecutionContext}

/** Created by dvoet on 6/27/17.
  */
trait TestSupport {
  def runAndWait[T](f: Awaitable[T]): T = Await.result(f, Duration.Inf)
  def runAndWait[T](f: IO[T]): T = f.unsafeRunSync()

  implicit val futureTimeout = Timeout(Span(10, Seconds))
  implicit val eqWorkbenchException: Eq[WorkbenchException] = (x: WorkbenchException, y: WorkbenchException) => x.getMessage == y.getMessage
  implicit val openTelemetry = FakeOpenTelemetryMetricsInterpreter

  val samRequestContext = SamRequestContext()

  def dummyResourceType(name: ResourceTypeName) =
    ResourceType(name, Set.empty, Set(ResourceRole(ResourceRoleName("owner"), Set.empty)), ResourceRoleName("owner"))
}

trait PropertyBasedTesting extends ScalaCheckPropertyChecks with Configuration with Matchers {
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful = 3)
}

object TestSupport extends TestSupport {
  private val executor = Executors.newCachedThreadPool()
  val blockingEc = ExecutionContext.fromExecutor(executor)

  val config = ConfigFactory.load()
  val appConfig = AppConfig.readConfig(config)
  val petServiceAccountConfig = appConfig.googleConfig.get.petServiceAccountConfig
  val googleServicesConfig = appConfig.googleConfig.get.googleServicesConfig
  val configResourceTypes = config.as[Map[String, ResourceType]]("resourceTypes").values.map(rt => rt.name -> rt).toMap
  val adminConfig = config.as[AdminConfig]("admin")
  val databaseEnabled = true
  val databaseEnabledClue = "-- skipping tests that talk to a real database"

  lazy val distributedLock = PostgresDistributedLockDAO[IO](dbRef, dbRef, appConfig.distributedLockConfig)
  def proxyEmail(workbenchUserId: WorkbenchUserId) = WorkbenchEmail(s"PROXY_$workbenchUserId@${googleServicesConfig.appsDomain}")
  def genGoogleSubjectId(): Option[GoogleSubjectId] = Option(GoogleSubjectId(genRandom(System.currentTimeMillis())))
  def genAzureB2CId(): AzureB2CId = AzureB2CId(genRandom(System.currentTimeMillis()))

  def genSamDependencies(
      resourceTypes: Map[ResourceTypeName, ResourceType] = Map.empty,
      googIamDAO: Option[GoogleIamDAO] = None,
      googleServicesConfig: GoogleServicesConfig = googleServicesConfig,
      cloudExtensions: Option[CloudExtensions] = None,
      googleDirectoryDAO: Option[GoogleDirectoryDAO] = None,
      policyAccessDAO: Option[AccessPolicyDAO] = None,
      policyEvaluatorServiceOpt: Option[PolicyEvaluatorService] = None,
      resourceServiceOpt: Option[ResourceService] = None
  )(implicit system: ActorSystem) = {
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val googleIamDAO = googIamDAO.getOrElse(new MockGoogleIamDAO())
    val policyDAO = policyAccessDAO.getOrElse(new MockAccessPolicyDAO(resourceTypes, directoryDAO))
    val notificationPubSubDAO = new MockGooglePubSubDAO()
    val googleGroupSyncPubSubDAO = new MockGooglePubSubDAO()
    val googleDisableUsersPubSubDAO = new MockGooglePubSubDAO()
    val googleKeyCachePubSubDAO = new MockGooglePubSubDAO()
    val googleStorageDAO = new MockGoogleStorageDAO()
    val googleProjectDAO = new MockGoogleProjectDAO()
    val notificationDAO = new PubSubNotificationDAO(notificationPubSubDAO, "foo")
    val cloudKeyCache = new GoogleKeyCache(
      distributedLock,
      googleIamDAO,
      googleStorageDAO,
      FakeGoogleStorageInterpreter,
      googleKeyCachePubSubDAO,
      googleServicesConfig,
      petServiceAccountConfig
    ) {
      // don't do any of the real boot stuff, it is all googley
      override def onBoot()(implicit system: ActorSystem): IO[Unit] = IO.unit
    }
    val googleExt = cloudExtensions.getOrElse(
      new GoogleExtensions(
        distributedLock,
        directoryDAO,
        policyDAO,
        googleDirectoryDAO,
        notificationPubSubDAO,
        googleGroupSyncPubSubDAO,
        googleDisableUsersPubSubDAO,
        googleIamDAO,
        googleStorageDAO,
        googleProjectDAO,
        cloudKeyCache,
        notificationDAO,
        FakeGoogleKmsInterpreter,
        googleServicesConfig,
        petServiceAccountConfig,
        resourceTypes,
        adminConfig.superAdminsGroup
      )
    )
    val policyEvaluatorService = policyEvaluatorServiceOpt.getOrElse(PolicyEvaluatorService(appConfig.emailDomain, resourceTypes, policyDAO, directoryDAO))
    val mockResourceService = resourceServiceOpt.getOrElse(
      new ResourceService(
        resourceTypes,
        policyEvaluatorService,
        policyDAO,
        directoryDAO,
        googleExt,
        emailDomain = "example.com",
        adminConfig.allowedEmailDomains
      )
    )
    val mockManagedGroupService =
      new ManagedGroupService(mockResourceService, policyEvaluatorService, resourceTypes, policyDAO, directoryDAO, googleExt, "example.com")
    val tosService = new TosService(directoryDAO, tosConfig)
    val azureService = new AzureService(MockCrlService(), directoryDAO, new MockAzureManagedResourceGroupDAO)
    SamDependencies(
      mockResourceService,
      policyEvaluatorService,
      tosService,
      new UserService(directoryDAO, googleExt, Seq.empty, tosService),
      new StatusService(directoryDAO, googleExt, dbRef),
      mockManagedGroupService,
      directoryDAO,
      policyDAO,
      googleExt,
      FakeOpenIDConnectConfiguration,
      azureService
    )
  }

  val tosConfig = config.as[TermsOfServiceConfig]("termsOfService")

  def genSamRoutes(samDependencies: SamDependencies, uInfo: SamUser)(implicit
      system: ActorSystem,
      materializer: Materializer,
      openTelemetry: OpenTelemetryMetrics[IO]
  ): SamRoutes = new SamRoutes(
    samDependencies.resourceService,
    samDependencies.userService,
    samDependencies.statusService,
    samDependencies.managedGroupService,
    samDependencies.tosService.tosConfig,
    samDependencies.directoryDAO,
    samDependencies.policyEvaluatorService,
    samDependencies.tosService,
    LiquibaseConfig("", false),
    samDependencies.oauth2Config,
    Some(samDependencies.azureService)
  ) with MockSamUserDirectives with GoogleExtensionRoutes {
    override val cloudExtensions: CloudExtensions = samDependencies.cloudExtensions
    override val googleExtensions: GoogleExtensions = samDependencies.cloudExtensions match {
      case extensions: GoogleExtensions => extensions
      case _ => null
    }
    override val googleGroupSynchronizer: GoogleGroupSynchronizer =
      if (samDependencies.cloudExtensions.isInstanceOf[GoogleExtensions]) {
        new GoogleGroupSynchronizer(
          googleExtensions.directoryDAO,
          googleExtensions.accessPolicyDAO,
          googleExtensions.googleDirectoryDAO,
          googleExtensions,
          googleExtensions.resourceTypes
        )
      } else null
    val googleKeyCache = samDependencies.cloudExtensions match {
      case extensions: GoogleExtensions => extensions.googleKeyCache
      case _ => null
    }
    override val user: SamUser = uInfo
    override val newSamUser: Option[SamUser] = Option(uInfo)
  }

  def genSamRoutesWithDefault(implicit system: ActorSystem, materializer: Materializer, openTelemetry: OpenTelemetryMetricsInterpreter[IO]): SamRoutes =
    genSamRoutes(genSamDependencies(), Generator.genWorkbenchUserBoth.sample.get)

  /*
  In unit tests there really is not a difference between read and write pools.
  Ideally I would not even have it. But I also want to have DatabaseNames enum and DbReference.init to use it.
  So the situation is a little messy and I favor having more mess on the test side than the production side
  (i.e. I don't want to add a new database name just for tests).
  So, just use the DatabaseNames.Read connection pool for tests.
   */
  lazy val dbRef = TestDbReference.init(config.as[LiquibaseConfig]("liquibase"), appConfig.samDatabaseConfig.samRead.dbName, TestSupport.blockingEc)

  def truncateAll: Int =
    if (databaseEnabled) {
      dbRef.inLocalTransaction { implicit session =>
        val tables = List(
          PolicyActionTable,
          PolicyRoleTable,
          PolicyTable,
          AuthDomainTable,
          ResourceTable,
          RoleActionTable,
          ResourceActionTable,
          NestedRoleTable,
          ResourceRoleTable,
          ResourceActionPatternTable,
          ResourceTypeTable,
          GroupMemberTable,
          GroupMemberFlatTable,
          PetServiceAccountTable,
          AzureManagedResourceGroupTable,
          PetManagedIdentityTable,
          UserTable,
          AccessInstructionsTable,
          GroupTable,
          LastQuotaErrorTable
        )

        tables
          .map(table =>
            withSQL {
              delete.from(table)
            }.update().apply()
          )
          .sum
      }
    } else {
      0
    }

  def newUserWithAcceptedTos(services: StandardSamUserDirectives, samUser: SamUser, samRequestContext: SamRequestContext): SamUser = {
    TestSupport.runAndWait(services.directoryDAO.createUser(samUser, samRequestContext))
    TestSupport.runAndWait(services.tosService.acceptTosStatus(samUser.id, samRequestContext))
    TestSupport.runAndWait(services.directoryDAO.loadUser(samUser.id, samRequestContext)).orNull
  }

  def newUserStatusWithAcceptedTos(userService: UserService, tosService: TosService, samUser: SamUser, samRequestContext: SamRequestContext): UserStatus = {
    TestSupport.runAndWait(userService.createUser(samUser, samRequestContext))
    TestSupport.runAndWait(tosService.acceptTosStatus(samUser.id, samRequestContext))
    TestSupport.runAndWait(userService.getUserStatus(samUser.id, userDetailsOnly = false, samRequestContext)).orNull
  }

  val enabledMapNoTosAccepted = Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true, "tosAccepted" -> false, "adminEnabled" -> true)
  val enabledMapTosAccepted = Map("ldap" -> true, "allUsersGroup" -> true, "google" -> true, "tosAccepted" -> true, "adminEnabled" -> true)
}

final case class SamDependencies(
    resourceService: ResourceService,
    policyEvaluatorService: PolicyEvaluatorService,
    tosService: TosService,
    userService: UserService,
    statusService: StatusService,
    managedGroupService: ManagedGroupService,
    directoryDAO: MockDirectoryDAO,
    policyDao: AccessPolicyDAO,
    cloudExtensions: CloudExtensions,
    oauth2Config: OpenIDConnectConfiguration,
    azureService: AzureService
)

object ConnectedTest extends Tag("connected test")
