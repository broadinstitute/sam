package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.unsafe.implicits.{global => globalEc}
import org.broadinstitute.dsde.workbench.sam.TestSupport.{databaseEnabled, databaseEnabledClue}
import org.broadinstitute.dsde.workbench.sam.{Generator, PropertyBasedTesting, TestSupport}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, PostgresDirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.model.TermsOfServiceAdherenceStatus
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.mockito.MockitoSugar.mock

import scala.concurrent.ExecutionContext.Implicits.global

class TosServiceSpec extends AnyFlatSpec with TestSupport with BeforeAndAfterAll with BeforeAndAfter with PropertyBasedTesting {

  lazy val dirDAO: DirectoryDAO = new PostgresDirectoryDAO(TestSupport.dbRef, TestSupport.dbRef)
  lazy val mockDirDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)

  val defaultUser = Generator.genWorkbenchUserBoth.sample.get
  val serviceAccountUser = Generator.genWorkbenchUserServiceAccount.sample.get

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    TestSupport.truncateAll
  }

  before {
    clearDatabase()
    TestSupport.truncateAll
  }

  protected def clearDatabase(): Unit =
    TestSupport.truncateAll

  "TosService.acceptTosStatus" should "accept the ToS for a user" in {
    assume(databaseEnabled, databaseEnabledClue)

    dirDAO.createUser(defaultUser, samRequestContext).unsafeRunSync()
    val tosService = new TosService(dirDAO, TestSupport.tosConfig)

    // accept and get ToS status
    val acceptTosStatusResult = tosService.acceptTosStatus(defaultUser.id, samRequestContext).unsafeRunSync()
    assert(acceptTosStatusResult, s"acceptTosStatus(${defaultUser.id}) should accept the tos for the user")
    val userAcceptedToS = dirDAO.loadUser(defaultUser.id, samRequestContext).unsafeRunSync().orNull
    val adherenceStatus = tosService.getTosAdherenceStatus(userAcceptedToS).unsafeRunSync()
    assert(adherenceStatus.userHasAcceptedLatestTos, s"getTosAcceptanceDetails(${defaultUser.id}) should get the tos for the user")
  }

  "TosService.rejectTosStatus" should "reject the ToS for a user" in {
    assume(databaseEnabled, databaseEnabledClue)

    dirDAO.createUser(defaultUser, samRequestContext).unsafeRunSync()
    val tosServiceV0 = new TosService(dirDAO, TestSupport.tosConfig.copy(version = "0"))
    tosServiceV0.acceptTosStatus(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe true

    // reject and get ToS status
    val tosServiceV2 = new TosService(dirDAO, TestSupport.tosConfig.copy(version = "0"))
    val rejectTosStatusResult = tosServiceV2.rejectTosStatus(defaultUser.id, samRequestContext).unsafeRunSync()
    assert(rejectTosStatusResult, s"rejectTosStatus(${defaultUser.id}) should reject the tos for the user")
    val userRejectedToS = dirDAO.loadUser(defaultUser.id, samRequestContext).unsafeRunSync().orNull
    val adherenceStatus = tosServiceV2.getTosAdherenceStatus(userRejectedToS).unsafeRunSync()
    assertResult(expected = false, s"getTosStatus(${defaultUser.id}) should have returned false")(actual = adherenceStatus.userHasAcceptedLatestTos)
  }

  "TosService" should "allow users to accept new version of ToS" in {
    assume(databaseEnabled, databaseEnabledClue)

    dirDAO.createUser(defaultUser, samRequestContext).unsafeRunSync()

    // Accept V0
    val tosServiceV0 = new TosService(dirDAO, TestSupport.tosConfig.copy(version = "0"))
    tosServiceV0.getTosAdherenceStatus(defaultUser).unsafeRunSync() shouldBe TermsOfServiceAdherenceStatus(defaultUser.id, false, false)
    tosServiceV0.acceptTosStatus(defaultUser.id, samRequestContext).unsafeRunSync() shouldBe true
    val userAcceptedV0 = dirDAO.loadUser(defaultUser.id, samRequestContext).unsafeRunSync().orNull
    val adherenceStatusV0 = tosServiceV0.getTosAdherenceStatus(userAcceptedV0).unsafeRunSync()
    adherenceStatusV0.userHasAcceptedLatestTos shouldBe true

    // Accept V2
    val tosServiceV2 = new TosService(dirDAO, TestSupport.tosConfig.copy(version = "2"))
    val adherenceStatusNotAcceptedLatest = tosServiceV2.getTosAdherenceStatus(userAcceptedV0).unsafeRunSync()
    adherenceStatusNotAcceptedLatest.userHasAcceptedLatestTos shouldBe false
    adherenceStatusNotAcceptedLatest.acceptedTosAllowsUsage shouldBe false

    tosServiceV2.acceptTosStatus(userAcceptedV0.id, samRequestContext).unsafeRunSync() shouldBe true
    val userAcceptedV2 = dirDAO.loadUser(userAcceptedV0.id, samRequestContext).unsafeRunSync().orNull
    val adherenceStatusV2 = tosServiceV2.getTosAdherenceStatus(userAcceptedV2).unsafeRunSync()
    adherenceStatusV2.userHasAcceptedLatestTos shouldBe true
    adherenceStatusV2.acceptedTosAllowsUsage shouldBe true
  }

  it should "always allow service account users to use Terra" in {
    assume(databaseEnabled, databaseEnabledClue)
    val tosServiceGracePeriodEnabled =
      new TosService(mockDirDAO, TestSupport.tosConfig.copy(version = "2"))
    val adherenceStatus = tosServiceGracePeriodEnabled.getTosAdherenceStatus(serviceAccountUser).unsafeRunSync()
    adherenceStatus.userHasAcceptedLatestTos shouldBe false
    adherenceStatus.acceptedTosAllowsUsage shouldBe true
  }

  /** | Case | Grace Period Enabled | Accepted Version | Current Version | User accepted latest | User Can Use Terra |
    * |:-----|:---------------------|:-----------------|:----------------|:---------------------|:-------------------|
    * | 1    | false                | null             | "2"             | false                | false              |
    * | 2    | false                | "0"              | "2"             | false                | false              |
    * | 3    | false                | "2"              | "2"             | true                 | true               |
    * | 4    | true                 | null             | "2"             | false                | false              |
    * | 5    | true                 | "0"              | "2"             | false                | true               |
    * | 6    | true                 | "2"              | "2"             | true                 | true               |
    */

  // CASE 1
  "TosService.getAdherenceStatus without a grace period" should "say a new user cannot use Terra and hasn't accepted ToS" in {
    val tosServiceGracePeriodEnabled =
      new TosService(mockDirDAO, TestSupport.tosConfig.copy(version = "2"))
    val adherenceStatus = tosServiceGracePeriodEnabled.getTosAdherenceStatus(defaultUser).unsafeRunSync()
    adherenceStatus.userHasAcceptedLatestTos shouldBe false
    adherenceStatus.acceptedTosAllowsUsage shouldBe false
  }

  // CASE 2
  it should "say a user that has accepted a previous ToS version cannot use Terra and hasn't accepted the latest ToS" in {
    val userAcceptedPreviousVersion = defaultUser.copy(acceptedTosVersion = Option("0"))
    val tosServiceGracePeriodEnabled =
      new TosService(mockDirDAO, TestSupport.tosConfig.copy(version = "2"))
    val adherenceStatus = tosServiceGracePeriodEnabled.getTosAdherenceStatus(userAcceptedPreviousVersion).unsafeRunSync()
    adherenceStatus.userHasAcceptedLatestTos shouldBe false
    adherenceStatus.acceptedTosAllowsUsage shouldBe false
  }

  // CASE 3
  it should "say a user that has accpeted the current ToS version can use Terra and does not need to accept ToS" in {
    val userAcceptedCurrentVersion = defaultUser.copy(acceptedTosVersion = Option("2"))
    val tosServiceGracePeriodEnabled =
      new TosService(mockDirDAO, TestSupport.tosConfig.copy(version = "2"))
    val adherenceStatus = tosServiceGracePeriodEnabled.getTosAdherenceStatus(userAcceptedCurrentVersion).unsafeRunSync()
    adherenceStatus.userHasAcceptedLatestTos shouldBe true
    adherenceStatus.acceptedTosAllowsUsage shouldBe true
  }

  // CASE 4
  "TosService.getAdherenceStatus with a grace period" should "say a new user cannot use Terra and hasn't accepted ToS" in {
    val tosServiceGracePeriodEnabled =
      new TosService(mockDirDAO, TestSupport.tosConfig.copy(version = "2", isGracePeriodEnabled = true))
    val adherenceStatus = tosServiceGracePeriodEnabled.getTosAdherenceStatus(defaultUser).unsafeRunSync()
    adherenceStatus.userHasAcceptedLatestTos shouldBe false
    adherenceStatus.acceptedTosAllowsUsage shouldBe false
  }

  // CASE 5
  it should "say a user that has accepted a previous ToS version can use Terra and hasn't accepted the latest ToS" in {
    val userAcceptedPreviousVersion = defaultUser.copy(acceptedTosVersion = Option("0"))
    val tosServiceGracePeriodEnabled =
      new TosService(mockDirDAO, TestSupport.tosConfig.copy(version = "2", isGracePeriodEnabled = true))
    val adherenceStatus = tosServiceGracePeriodEnabled.getTosAdherenceStatus(userAcceptedPreviousVersion).unsafeRunSync()
    adherenceStatus.userHasAcceptedLatestTos shouldBe false
    adherenceStatus.acceptedTosAllowsUsage shouldBe true
  }

  // CASE 6
  it should "say a user that has accepted the current ToS version can use Terra and does not need to accept ToS" in {
    assume(databaseEnabled, databaseEnabledClue)
    val userAcceptedCurrentVersion = defaultUser.copy(acceptedTosVersion = Option("2"))
    val tosServiceGracePeriodEnabled =
      new TosService(mockDirDAO, TestSupport.tosConfig.copy(version = "2", isGracePeriodEnabled = true))
    val adherenceStatus = tosServiceGracePeriodEnabled.getTosAdherenceStatus(userAcceptedCurrentVersion).unsafeRunSync()
    adherenceStatus.userHasAcceptedLatestTos shouldBe true
    adherenceStatus.acceptedTosAllowsUsage shouldBe true
  }
}
