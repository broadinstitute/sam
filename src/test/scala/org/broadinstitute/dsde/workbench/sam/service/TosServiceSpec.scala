package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import cats.effect.unsafe.implicits.{global => globalEc}
import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.TestSupport.tosConfig
import org.broadinstitute.dsde.workbench.sam.{Generator, PropertyBasedTesting, TestSupport}
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{RETURNS_SMART_NULLS, verify, when}
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatestplus.mockito.MockitoSugar.mock

import scala.concurrent.ExecutionContext.Implicits.global

class TosServiceSpec extends AnyFreeSpec with TestSupport with BeforeAndAfterAll with BeforeAndAfter with PropertyBasedTesting {

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

  "TosService" - {
    "accepts the ToS for a user" in {
      when(mockDirDAO.acceptTermsOfService(any[WorkbenchUserId], any[String], any[SamRequestContext]))
        .thenReturn(IO.pure(true))

      val tosService = new TosService(mockDirDAO, TestSupport.tosConfig)

      // accept and get ToS status
      val acceptTosStatusResult = tosService.acceptTosStatus(defaultUser.id, samRequestContext).unsafeRunSync()
      assert(acceptTosStatusResult, s"acceptTosStatus(${defaultUser.id}) should accept the tos for the user")

      verify(mockDirDAO).acceptTermsOfService(defaultUser.id, tosConfig.version, samRequestContext)
    }

    "rejects the ToS for a user" in {
      when(mockDirDAO.rejectTermsOfService(any[WorkbenchUserId], any[SamRequestContext]))
        .thenReturn(IO.pure(true))

      // reject and get ToS status
      val tosService = new TosService(mockDirDAO, TestSupport.tosConfig)
      val rejectTosStatusResult = tosService.rejectTosStatus(defaultUser.id, samRequestContext).unsafeRunSync()

      assert(rejectTosStatusResult, s"rejectTosStatus(${defaultUser.id}) should reject the tos for the user")
      verify(mockDirDAO).rejectTermsOfService(defaultUser.id, samRequestContext)
    }

    "always allows service account users to use the system" in {
      val tosService =
        new TosService(mockDirDAO, TestSupport.tosConfig.copy(version = "2"))
      val complianceStatus = tosService.getTosComplianceStatus(serviceAccountUser).unsafeRunSync()
      complianceStatus.userHasAcceptedLatestTos shouldBe false
      complianceStatus.permitsSystemUsage shouldBe true
    }

    val withoutGracePeriod = "without the grace period enabled"
    val withGracePeriod = " with the grace period enabled"
    val cannotUseTheSystem = "says the user cannot use the system"
    val canUseTheSystem = "says the user can use the system"
    val tosService = new TosService(mockDirDAO, TestSupport.tosConfig.copy(version = "2"))
    val tosServiceGracePeriodEnabled =
      new TosService(mockDirDAO, TestSupport.tosConfig.copy(version = "2", isGracePeriodEnabled = true))

    /** | Case | Grace Period Enabled | Accepted Version | Current Version | User accepted latest | Permits system usage |
      * |:-----|:---------------------|:-----------------|:----------------|:---------------------|:---------------------|
      * | 1    | false                | null             | "2"             | false                | false                |
      * | 2    | false                | "0"              | "2"             | false                | false                |
      * | 3    | false                | "2"              | "2"             | true                 | true                 |
      * | 4    | true                 | null             | "2"             | false                | false                |
      * | 5    | true                 | "0"              | "2"             | false                | true                 |
      * | 6    | true                 | "2"              | "2"             | true                 | true                 |
      */

    "when the user has not accepted any ToS version" - {
      "says the user has not accepted the latest version" in {
        val complianceStatus = tosService.getTosComplianceStatus(defaultUser).unsafeRunSync()
        complianceStatus.userHasAcceptedLatestTos shouldBe false
      }
      withoutGracePeriod - {
        cannotUseTheSystem in {
          // CASE 1
          val complianceStatus = tosService.getTosComplianceStatus(defaultUser).unsafeRunSync()
          complianceStatus.permitsSystemUsage shouldBe false
        }
      }
      withGracePeriod - {
        cannotUseTheSystem in {
          // CASE 4
          val complianceStatus = tosServiceGracePeriodEnabled.getTosComplianceStatus(defaultUser).unsafeRunSync()
          complianceStatus.permitsSystemUsage shouldBe false
        }
      }
    }
    "when the user has accepted a non-current ToS version" - {
      val userAcceptedPreviousVersion = defaultUser.copy(acceptedTosVersion = Option("0"))
      "says the user has not accepted the latest version" in {
        val complianceStatus = tosService.getTosComplianceStatus(userAcceptedPreviousVersion).unsafeRunSync()
        complianceStatus.userHasAcceptedLatestTos shouldBe false
      }
      withoutGracePeriod - {
        cannotUseTheSystem in {
          // CASE 2
          val complianceStatus = tosService.getTosComplianceStatus(userAcceptedPreviousVersion).unsafeRunSync()
          complianceStatus.permitsSystemUsage shouldBe false
        }
      }
      withGracePeriod - {
        canUseTheSystem in {
          // CASE 5
          val complianceStatus = tosServiceGracePeriodEnabled.getTosComplianceStatus(userAcceptedPreviousVersion).unsafeRunSync()
          complianceStatus.permitsSystemUsage shouldBe true
        }
      }
    }
    "when the user has accepted the current ToS version" - {
      val userAcceptedCurrentVersion = defaultUser.copy(acceptedTosVersion = Option("2"))
      "says the user has accepted the latest version" in {
        val complianceStatus = tosService.getTosComplianceStatus(userAcceptedCurrentVersion).unsafeRunSync()
        complianceStatus.userHasAcceptedLatestTos shouldBe true
      }
      withoutGracePeriod - {
        canUseTheSystem in {
          // CASE 3
          val complianceStatus = tosService.getTosComplianceStatus(userAcceptedCurrentVersion).unsafeRunSync()
          complianceStatus.permitsSystemUsage shouldBe true
        }
      }
      withGracePeriod - {
        canUseTheSystem in {
          // CASE 6
          val complianceStatus = tosServiceGracePeriodEnabled.getTosComplianceStatus(userAcceptedCurrentVersion).unsafeRunSync()
          complianceStatus.permitsSystemUsage shouldBe true
        }
      }
    }
  }
}
