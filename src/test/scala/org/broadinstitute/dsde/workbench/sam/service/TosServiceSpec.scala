package org.broadinstitute.dsde.workbench.sam.service

import cats.effect.IO
import cats.effect.unsafe.implicits.{global => globalEc}
import org.broadinstitute.dsde.workbench.model.WorkbenchUserId
import org.broadinstitute.dsde.workbench.sam.TestSupport.tosConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.db.tables.TosTable
import org.broadinstitute.dsde.workbench.sam.model.SamUserTos
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.{Generator, PropertyBasedTesting, TestSupport}
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.mockito.scalatest.MockitoSugar
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global

class TosServiceSpec extends AnyFreeSpec with TestSupport with BeforeAndAfterAll with BeforeAndAfter with PropertyBasedTesting with MockitoSugar {

  lazy val dirDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)

  val defaultUser = Generator.genWorkbenchUserBoth.sample.get
  val serviceAccountUser = Generator.genWorkbenchUserServiceAccount.sample.get

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    TestSupport.truncateAll
  }

  before {
    clearDatabase()
    TestSupport.truncateAll
    reset(dirDAO)
  }

  protected def clearDatabase(): Unit =
    TestSupport.truncateAll

  "TosService" - {
    "is enabled by default" in {
      TestSupport.tosConfig.isTosEnabled shouldBe true
    }

    "accepts the ToS for a user" in {
      when(dirDAO.acceptTermsOfService(any[WorkbenchUserId], any[String], any[SamRequestContext]))
        .thenReturn(IO.pure(true))

      val tosService = new TosService(dirDAO, TestSupport.tosConfig)

      // accept and get ToS status
      val acceptTosStatusResult = tosService.acceptTosStatus(defaultUser.id, samRequestContext).unsafeRunSync()
      acceptTosStatusResult shouldBe true

      verify(dirDAO).acceptTermsOfService(defaultUser.id, tosConfig.version, samRequestContext)
    }

    "rejects the ToS for a user" in {
      when(dirDAO.rejectTermsOfService(any[WorkbenchUserId], any[String], any[SamRequestContext]))
        .thenReturn(IO.pure(true))

      // reject and get ToS status
      val tosService = new TosService(dirDAO, TestSupport.tosConfig)
      val rejectTosStatusResult = tosService.rejectTosStatus(defaultUser.id, samRequestContext).unsafeRunSync()

      rejectTosStatusResult shouldBe true
      verify(dirDAO).rejectTermsOfService(defaultUser.id, tosConfig.version, samRequestContext)
    }

    "always allows service account users to use the system" in {
      val tosVersion = "2"
      val tosService =
        new TosService(dirDAO, TestSupport.tosConfig.copy(version = tosVersion))
      when(dirDAO.getUserTos(serviceAccountUser.id, tosVersion, samRequestContext))
        .thenReturn(IO.pure(Some(SamUserTos(serviceAccountUser.id, tosVersion, TosTable.ACCEPT, Instant.now()))))
      val complianceStatus = tosService.getTosComplianceStatus(serviceAccountUser, samRequestContext).unsafeRunSync()
      complianceStatus.permitsSystemUsage shouldBe true
    }

    val tosVersion = "2"
    val withoutGracePeriod = "without the grace period enabled"
    val withGracePeriod = " with the grace period enabled"
    val cannotUseTheSystem = "says the user cannot use the system"
    val canUseTheSystem = "says the user can use the system"
    val tosServiceV2 = new TosService(dirDAO, TestSupport.tosConfig.copy(version = tosVersion))
    val tosServiceV2GracePeriodEnabled =
      new TosService(dirDAO, TestSupport.tosConfig.copy(version = tosVersion, isGracePeriodEnabled = true))

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
        when(dirDAO.getUserTos(defaultUser.id, tosVersion, samRequestContext))
          .thenReturn(IO.pure(None))
        val complianceStatus = tosServiceV2.getTosComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
        complianceStatus.userHasAcceptedLatestTos shouldBe false
      }
      withoutGracePeriod - {
        cannotUseTheSystem in {
          when(dirDAO.getUserTos(defaultUser.id, tosVersion, samRequestContext))
            .thenReturn(IO.pure(None))
          // CASE 1
          val complianceStatus = tosServiceV2.getTosComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
          complianceStatus.permitsSystemUsage shouldBe false
        }
      }
      withGracePeriod - {
        cannotUseTheSystem in {
          when(dirDAO.getUserTos(defaultUser.id, tosVersion, samRequestContext))
            .thenReturn(IO.pure(None))
          // CASE 4
          val complianceStatus = tosServiceV2GracePeriodEnabled.getTosComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
          complianceStatus.permitsSystemUsage shouldBe false
        }
      }
    }
    "when the user has accepted a non-current ToS version" - {
      "says the user has not accepted the latest version" in {
        when(dirDAO.getUserTos(defaultUser.id, tosVersion, samRequestContext))
          .thenReturn(IO.pure(None))
        val complianceStatus = tosServiceV2.getTosComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
        complianceStatus.userHasAcceptedLatestTos shouldBe false
      }
      withoutGracePeriod - {
        cannotUseTheSystem in {
          when(dirDAO.getUserTos(defaultUser.id, tosVersion, samRequestContext))
            .thenReturn(IO.pure(None))
          // CASE 2
          val complianceStatus = tosServiceV2.getTosComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
          complianceStatus.permitsSystemUsage shouldBe false
        }
      }
      withGracePeriod - {
        canUseTheSystem in {
          when(dirDAO.getUserTos(defaultUser.id, tosVersion, samRequestContext))
            .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, tosVersion, TosTable.ACCEPT, Instant.now()))))
          // CASE 5
          val complianceStatus = tosServiceV2GracePeriodEnabled.getTosComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
          complianceStatus.permitsSystemUsage shouldBe true
        }
      }
    }
    "when the user has accepted the current ToS version" - {
      "says the user has accepted the latest version" in {
        when(dirDAO.getUserTos(defaultUser.id, tosVersion, samRequestContext))
          .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, tosVersion, TosTable.ACCEPT, Instant.now()))))
        val complianceStatus = tosServiceV2.getTosComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
        complianceStatus.userHasAcceptedLatestTos shouldBe true
      }
      withoutGracePeriod - {
        canUseTheSystem in {
          when(dirDAO.getUserTos(defaultUser.id, tosVersion, samRequestContext))
            .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, tosVersion, TosTable.ACCEPT, Instant.now()))))
          // CASE 3
          val complianceStatus = tosServiceV2.getTosComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
          complianceStatus.permitsSystemUsage shouldBe true
        }
      }
      withGracePeriod - {
        canUseTheSystem in {
          when(dirDAO.getUserTos(defaultUser.id, tosVersion, samRequestContext))
            .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, tosVersion, TosTable.ACCEPT, Instant.now()))))
          // CASE 6
          val complianceStatus = tosServiceV2GracePeriodEnabled.getTosComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
          complianceStatus.permitsSystemUsage shouldBe true
        }
      }
    }

    "when the user has rejected the latest ToS version" - {
      "says the user has rejected the latest version" in {
        when(dirDAO.getUserTos(defaultUser.id, tosVersion, samRequestContext))
          .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, tosVersion, TosTable.REJECT, Instant.now()))))
        val complianceStatus = tosServiceV2.getTosComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
        complianceStatus.userHasAcceptedLatestTos shouldBe false
      }
      withoutGracePeriod - {
        cannotUseTheSystem in {
          when(dirDAO.getUserTos(defaultUser.id, tosVersion, samRequestContext))
            .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, tosVersion, TosTable.REJECT, Instant.now()))))
          // CASE 1
          val complianceStatus = tosServiceV2.getTosComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
          complianceStatus.permitsSystemUsage shouldBe false
        }
      }
      withGracePeriod - {
        cannotUseTheSystem in {
          when(dirDAO.getUserTos(defaultUser.id, tosVersion, samRequestContext))
            .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, tosVersion, TosTable.REJECT, Instant.now()))))
          // CASE 4
          val complianceStatus = tosServiceV2GracePeriodEnabled.getTosComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
          complianceStatus.permitsSystemUsage shouldBe false
        }
      }
    }
    "when a service account is using the api" - {
      "let it use the api regardless of tos status" in {
        when(dirDAO.getUserTos(serviceAccountUser.id, tosVersion, samRequestContext))
          .thenReturn(IO.pure(None))
        val complianceStatus = tosServiceV2.getTosComplianceStatus(serviceAccountUser, samRequestContext).unsafeRunSync()
        complianceStatus.permitsSystemUsage shouldBe true
      }
    }
  }
}
