package org.broadinstitute.dsde.workbench.sam

import java.net.URI
import java.time.Instant
import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.stream.Materializer
import cats.effect.IO
import cats.kernel.Eq
import com.google.cloud.firestore.{DocumentSnapshot, Firestore, Transaction}
import com.typesafe.config.ConfigFactory
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.dataaccess.PubSubNotificationDAO
import org.broadinstitute.dsde.workbench.google2.mock.FakeGoogleStorageInterpreter
import org.broadinstitute.dsde.workbench.google.mock._
import org.broadinstitute.dsde.workbench.google2.util.DistributedLock
import org.broadinstitute.dsde.workbench.google2.{CollectionName, Document, GoogleFirestoreService}
import org.broadinstitute.dsde.workbench.google.{GoogleDirectoryDAO, GoogleIamDAO}
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.api._
import org.broadinstitute.dsde.workbench.sam.config.AppConfig._
import org.broadinstitute.dsde.workbench.sam.config._
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AccessPolicyDAO, MockAccessPolicyDAO, MockDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.db.{DatabaseNames, DbReference}
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.google.{GoogleExtensionRoutes, GoogleExtensions, GoogleGroupSynchronizer, GoogleKeyCache}
import org.broadinstitute.dsde.workbench.sam.model._
import org.broadinstitute.dsde.workbench.sam.service.UserService._
import org.broadinstitute.dsde.workbench.sam.service._
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.scalatest.prop.Configuration
import org.scalatest.time.{Seconds, Span}
import scalikejdbc.withSQL
import scalikejdbc.QueryDSL.delete

import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Awaitable, ExecutionContext}
import org.scalatest.matchers.should.Matchers

/**
  * Created by dvoet on 6/27/17.
  */
trait TestSupport{
  def runAndWait[T](f: Awaitable[T]): T = Await.result(f, Duration.Inf)
  def runAndWait[T](f: IO[T]): T = f.unsafeRunSync()

  implicit val futureTimeout = Timeout(Span(10, Seconds))
  implicit val cs = IO.contextShift(scala.concurrent.ExecutionContext.global)
  implicit val timer = IO.timer(scala.concurrent.ExecutionContext.global)
  implicit val eqWorkbenchException: Eq[WorkbenchException] = (x: WorkbenchException, y: WorkbenchException) => x.getMessage == y.getMessage

  val samRequestContext = SamRequestContext(None)

  def dummyResourceType(name: ResourceTypeName) = ResourceType(name, Set.empty, Set(ResourceRole(ResourceRoleName("owner"), Set.empty)), ResourceRoleName("owner"))
}

trait PropertyBasedTesting extends ScalaCheckPropertyChecks with Configuration with Matchers {
  implicit override val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful = 3)
}

object TestSupport extends TestSupport {
  private val executor = Executors.newCachedThreadPool()
  val blockingEc = ExecutionContext.fromExecutor(executor)

  implicit val eqWorkbenchExceptionErrorReport: Eq[WorkbenchExceptionWithErrorReport] =
    (x: WorkbenchExceptionWithErrorReport, y: WorkbenchExceptionWithErrorReport) =>
      x.errorReport.statusCode == y.errorReport.statusCode && x.errorReport.message == y.errorReport.message
  val config = ConfigFactory.load()
  val appConfig = AppConfig.readConfig(config)
  val petServiceAccountConfig = appConfig.googleConfig.get.petServiceAccountConfig
  val googleServicesConfig = appConfig.googleConfig.get.googleServicesConfig
  val configResourceTypes = config.as[Map[String, ResourceType]]("resourceTypes").values.map(rt => rt.name -> rt).toMap
  val defaultUserEmail = WorkbenchEmail("newuser@new.com")
  val directoryConfig = config.as[DirectoryConfig]("directory")
  val schemaLockConfig = config.as[SchemaLockConfig]("schemaLock")
  val dirURI = new URI(directoryConfig.directoryUrl)

  val fakeDistributedLock = DistributedLock[IO]("", appConfig.distributedLockConfig, FakeGoogleFirestore)
  def proxyEmail(workbenchUserId: WorkbenchUserId) = WorkbenchEmail(s"PROXY_$workbenchUserId@${googleServicesConfig.appsDomain}")
  def genGoogleSubjectId(): GoogleSubjectId = GoogleSubjectId(genRandom(System.currentTimeMillis()))
  def genIdentityConcentratorId(): IdentityConcentratorId = IdentityConcentratorId(genRandom(System.currentTimeMillis()))

  def genSamDependencies(resourceTypes: Map[ResourceTypeName, ResourceType] = Map.empty, googIamDAO: Option[GoogleIamDAO] = None, googleServicesConfig: GoogleServicesConfig = googleServicesConfig, cloudExtensions: Option[CloudExtensions] = None, googleDirectoryDAO: Option[GoogleDirectoryDAO] = None, policyAccessDAO: Option[AccessPolicyDAO] = None)(implicit system: ActorSystem) = {
    val googleDirectoryDAO = new MockGoogleDirectoryDAO()
    val directoryDAO = new MockDirectoryDAO()
    val registrationDAO = new MockDirectoryDAO()
    val googleIamDAO = googIamDAO.getOrElse(new MockGoogleIamDAO())
    val policyDAO = policyAccessDAO.getOrElse(new MockAccessPolicyDAO(resourceTypes))
    val pubSubDAO = new MockGooglePubSubDAO()
    val googleStorageDAO = new MockGoogleStorageDAO()
    val googleProjectDAO = new MockGoogleProjectDAO()
    val notificationDAO = new PubSubNotificationDAO(pubSubDAO, "foo")
    val cloudKeyCache = new GoogleKeyCache(fakeDistributedLock, googleIamDAO, googleStorageDAO, FakeGoogleStorageInterpreter, pubSubDAO, googleServicesConfig, petServiceAccountConfig)
    val googleExt = cloudExtensions.getOrElse(new GoogleExtensions(
      fakeDistributedLock,
      directoryDAO,
      registrationDAO,
      policyDAO,
      googleDirectoryDAO,
      pubSubDAO,
      googleIamDAO,
      googleStorageDAO,
      googleProjectDAO,
      cloudKeyCache,
      notificationDAO,
      FakeGoogleKmsInterpreter,
      googleServicesConfig,
      petServiceAccountConfig,
      resourceTypes))
    val policyEvaluatorService = PolicyEvaluatorService(appConfig.emailDomain, resourceTypes, policyDAO, directoryDAO)
    val mockResourceService = new ResourceService(resourceTypes, policyEvaluatorService, policyDAO, directoryDAO, googleExt, "example.com")
    val mockManagedGroupService = new ManagedGroupService(mockResourceService, policyEvaluatorService, resourceTypes, policyDAO, directoryDAO, googleExt, "example.com")

    SamDependencies(mockResourceService, policyEvaluatorService, new UserService(directoryDAO, googleExt, registrationDAO, Seq.empty), new StatusService(directoryDAO, googleExt, dbRef), mockManagedGroupService, directoryDAO, policyDAO, googleExt)
  }

  def genSamRoutes(samDependencies: SamDependencies, uInfo: UserInfo)(implicit system: ActorSystem, materializer: Materializer): SamRoutes = new SamRoutes(samDependencies.resourceService, samDependencies.userService, samDependencies.statusService, samDependencies.managedGroupService, null, samDependencies.directoryDAO, samDependencies.policyEvaluatorService, LiquibaseConfig("", false))
    with MockUserInfoDirectives
    with GoogleExtensionRoutes {
      override val cloudExtensions: CloudExtensions = samDependencies.cloudExtensions
      override val googleExtensions: GoogleExtensions = if(samDependencies.cloudExtensions.isInstanceOf[GoogleExtensions]) samDependencies.cloudExtensions.asInstanceOf[GoogleExtensions] else null
      override val googleGroupSynchronizer: GoogleGroupSynchronizer = {
        if(samDependencies.cloudExtensions.isInstanceOf[GoogleExtensions]) {
          new GoogleGroupSynchronizer(googleExtensions.directoryDAO, googleExtensions.accessPolicyDAO, googleExtensions.googleDirectoryDAO, googleExtensions, googleExtensions.resourceTypes)(executionContext)
        } else null
      }
      val googleKeyCache = if(samDependencies.cloudExtensions.isInstanceOf[GoogleExtensions])samDependencies.cloudExtensions.asInstanceOf[GoogleExtensions].googleKeyCache else null
      override val userInfo: UserInfo = uInfo
      override val createWorkbenchUser: Option[CreateWorkbenchUser] = Option(CreateWorkbenchUser(uInfo.userId, GoogleSubjectId(uInfo.userId.value), uInfo.userEmail, Option(IdentityConcentratorId(uInfo.userId.value))))
  }

  def genSamRoutesWithDefault(implicit system: ActorSystem, materializer: Materializer): SamRoutes = genSamRoutes(genSamDependencies(), UserInfo(OAuth2BearerToken(""), genWorkbenchUserId(System.currentTimeMillis()), defaultUserEmail, 3600))

  /*
  In unit tests there really is not a difference between read and write pools.
  Ideally I would not even have it. But I also want to have DatabaseNames enum and DbReference.init to use it.
  So the situation is a little messy and I favor having more mess on the test side than the production side
  (i.e. I don't want to add a new database name just for tests).
  So, just use the DatabaseNames.Read connection pool for tests.
   */
  lazy val dbRef = DbReference.init(config.as[LiquibaseConfig]("liquibase"), DatabaseNames.Read, TestSupport.blockingEc)

  def truncateAll: Int = {
    dbRef.inLocalTransaction { implicit session =>
      val tables = List(PolicyActionTable,
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
        UserTable,
        AccessInstructionsTable,
        GroupTable)

      tables.map(table => withSQL{
        delete.from(table)
      }.update().apply()).sum
    }
  }
}

final case class SamDependencies(resourceService: ResourceService, policyEvaluatorService: PolicyEvaluatorService, userService: UserService, statusService: StatusService, managedGroupService: ManagedGroupService, directoryDAO: MockDirectoryDAO, policyDao: AccessPolicyDAO, val cloudExtensions: CloudExtensions)

object FakeGoogleFirestore extends GoogleFirestoreService[IO]{
  override def set(
      collectionName: CollectionName,
      document: Document,
      dataMap: Map[String, Any])
    : IO[Instant] = ???
  override def get(
      collectionName: CollectionName,
      document: Document)
    : IO[DocumentSnapshot] = ???
  override def transaction[A](
      ops: (Firestore, Transaction) => IO[A])
    : IO[A] = IO.unit.map(_.asInstanceOf[A]) //create a transaction always finishes so that retrieving lock alway succeeds
}
