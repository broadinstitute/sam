package org.broadinstitute.dsde.workbench.sam.service

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.testkit.TestKit
import cats.effect.IO
import cats.effect.unsafe.implicits.{global => globalEc}
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchExceptionWithErrorReport, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.TestSupport.tosConfig
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, MockDirectoryDaoBuilder}
import org.broadinstitute.dsde.workbench.sam.db.tables.TosTable
import org.broadinstitute.dsde.workbench.sam.matchers.{TermsOfServiceDetailsMatchers, TimeMatchers}
import org.broadinstitute.dsde.workbench.sam.model.api.TermsOfServiceConfigResponse
import org.broadinstitute.dsde.workbench.sam.model.{BasicWorkbenchGroup, SamUserTos, TermsOfServiceDetails, TermsOfServiceHistory}
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.broadinstitute.dsde.workbench.sam.{Generator, PropertyBasedTesting, TestSupport}
import org.mockito.Mockito.RETURNS_SMART_NULLS
import org.mockito.scalatest.MockitoSugar
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, OptionValues}

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global

class TosServiceSpec(_system: ActorSystem)
    extends TestKit(_system)
    with AnyFreeSpecLike
    with TestSupport
    with BeforeAndAfterAll
    with BeforeAndAfter
    with PropertyBasedTesting
    with MockitoSugar
    with TimeMatchers
    with OptionValues
    with TermsOfServiceDetailsMatchers {

  val allUsersGroup: BasicWorkbenchGroup = BasicWorkbenchGroup(CloudExtensions.allUsersGroupName, Set(), WorkbenchEmail("all_users@fake.com"))

  def this() = this(ActorSystem("TosServiceSpec"))

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    TestSupport.truncateAll
  }
  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  private lazy val dirDAO = mock[DirectoryDAO](RETURNS_SMART_NULLS)

  private val defaultUser = Generator.genWorkbenchUserBoth.sample.get
  private val serviceAccountUser = Generator.genWorkbenchUserServiceAccount.sample.get
  private val uamiUser = Generator.genWorkbenchUserAzureUami.sample.get

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

    "returns configurations" in {
      val tosService = new TosService(NoExtensions, dirDAO, TestSupport.tosConfig)

      // accept and get ToS status
      val tosConfigResponse = tosService.getTermsOfServiceConfig().unsafeRunSync()
      tosConfigResponse shouldBe TermsOfServiceConfigResponse(
        enforced = true,
        currentVersion = "0",
        inGracePeriod = false,
        inRollingAcceptanceWindow = false
      )
    }

    "when getting terms of service documents " - {
      "loads the Terms of Service text when TosService is instantiated" in {
        val tosService = new TosService(NoExtensions, dirDAO, TestSupport.tosConfig.copy(version = "2"))
        tosService.termsOfServiceText contains "Test Terms of Service"
        tosService.privacyPolicyText contains "Test Privacy Policy"
      }

      "returns terms of service text when no query parameters are passed" in {
        val tosService = new TosService(NoExtensions, dirDAO, TestSupport.tosConfig)

        val tosText = tosService.getTermsOfServiceTexts(Set.empty).unsafeRunSync()
        tosText shouldBe tosService.termsOfServiceText
      }

      "returns terms of service text by default" in {
        val tosService = new TosService(NoExtensions, dirDAO, TestSupport.tosConfig)

        val tosText = tosService.getTermsOfServiceTexts(Set("termsOfService")).unsafeRunSync()
        tosText shouldBe tosService.termsOfServiceText
      }

      "returns privacy policy text" in {
        val tosService = new TosService(NoExtensions, dirDAO, TestSupport.tosConfig)

        val tosText = tosService.getTermsOfServiceTexts(Set("privacyPolicy")).unsafeRunSync()
        tosText shouldBe tosService.privacyPolicyText
      }

      "returns privacy policy text and terms of service text" in {
        val tosService = new TosService(NoExtensions, dirDAO, TestSupport.tosConfig)

        val tosText = tosService.getTermsOfServiceTexts(Set("privacyPolicy", "termsOfService")).unsafeRunSync()
        tosText shouldBe s"${tosService.termsOfServiceText}\n\n${tosService.privacyPolicyText}"
      }
    }

    "when a user is accepting/rejecting the Terms of Service" - {

      "accepts the ToS for a user" in {
        when(dirDAO.acceptTermsOfService(any[WorkbenchUserId], any[String], any[SamRequestContext]))
          .thenReturn(IO.pure(true))

        val tosService = new TosService(NoExtensions, dirDAO, TestSupport.tosConfig)

        // accept and get ToS status
        val acceptTermsOfServiceResult = tosService.acceptCurrentTermsOfService(defaultUser.id, samRequestContext).unsafeRunSync()
        acceptTermsOfServiceResult shouldBe true

        verify(dirDAO).acceptTermsOfService(defaultUser.id, tosConfig.version, samRequestContext)
      }

      "rejects the ToS for a user" in {
        when(dirDAO.rejectTermsOfService(any[WorkbenchUserId], any[String], any[SamRequestContext]))
          .thenReturn(IO.pure(true))

        // reject and get ToS status
        val tosService = new TosService(NoExtensions, dirDAO, TestSupport.tosConfig)
        val rejectTermsOfServiceResult = tosService.rejectCurrentTermsOfService(defaultUser.id, samRequestContext).unsafeRunSync()

        rejectTermsOfServiceResult shouldBe true
        verify(dirDAO).rejectTermsOfService(defaultUser.id, tosConfig.version, samRequestContext)
      }
    }

    "when presented with machine-account users" - {

      "always allows service account users to use the system" in {
        val tosVersion = "2"
        val previousTosVersion = Option("1")
        val tosService =
          new TosService(NoExtensions, dirDAO, TestSupport.tosConfig.copy(version = tosVersion, previousVersion = previousTosVersion))
        when(dirDAO.getUserTermsOfService(serviceAccountUser.id, samRequestContext)).thenReturn(IO.pure(None))

        when(dirDAO.getUserTermsOfServiceVersion(serviceAccountUser.id, previousTosVersion, samRequestContext)).thenReturn(IO.pure(None))

        val complianceStatus = tosService.getTermsOfServiceComplianceStatus(serviceAccountUser, samRequestContext).unsafeRunSync()
        complianceStatus.permitsSystemUsage shouldBe true
      }

      "always allows UAMI users to use the system" in {
        val tosVersion = "2"
        val previousTosVersion = Option("1")
        val tosService =
          new TosService(NoExtensions, dirDAO, TestSupport.tosConfig.copy(version = tosVersion, previousVersion = previousTosVersion))
        when(dirDAO.getUserTermsOfService(uamiUser.id, samRequestContext)).thenReturn(IO.pure(None))

        when(dirDAO.getUserTermsOfServiceVersion(uamiUser.id, previousTosVersion, samRequestContext)).thenReturn(IO.pure(None))

        val complianceStatus = tosService.getTermsOfServiceComplianceStatus(uamiUser, samRequestContext).unsafeRunSync()
        complianceStatus.permitsSystemUsage shouldBe true
      }
    }

    val tosVersion = "2"
    val previousVersion = "1"
    val previousVersionOpt = Option(previousVersion)
    val rollingAcceptanceWindowExpiration = Option(Instant.now().plusSeconds(3600))
    val withoutGracePeriod = "without the grace period enabled"
    val withGracePeriod = " with the grace period enabled"
    val withoutRollingAcceptanceWindow = "outside of the rolling acceptance window"
    val withRollingAcceptanceWindow = " inside of the rolling acceptance window"
    val cannotUseTheSystem = "says the user cannot use the system"
    val canUseTheSystem = "says the user can use the system"
    val tosServiceV2GracePeriodDisabledAcceptanceWindowDisabled = new TosService(
      NoExtensions,
      dirDAO,
      TestSupport.tosConfig.copy(
        isTosEnabled = true,
        isGracePeriodEnabled = false,
        version = tosVersion,
        previousVersion = previousVersionOpt
      )
    )
    val tosServiceV2GracePeriodEnabledAcceptanceWindowDisabled =
      new TosService(
        NoExtensions,
        dirDAO,
        TestSupport.tosConfig
          .copy(version = tosVersion, isGracePeriodEnabled = true, previousVersion = previousVersionOpt)
      )
    val tosServiceV2GracePeriodDisabledAcceptanceWindowEnabled = new TosService(
      NoExtensions,
      dirDAO,
      TestSupport.tosConfig.copy(
        isTosEnabled = true,
        isGracePeriodEnabled = false,
        version = tosVersion,
        rollingAcceptanceWindowExpiration = rollingAcceptanceWindowExpiration,
        previousVersion = previousVersionOpt
      )
    )
    val tosServiceV2GracePeriodEnabledAcceptanceWindowEnabled = new TosService(
      NoExtensions,
      dirDAO,
      TestSupport.tosConfig.copy(
        isTosEnabled = true,
        isGracePeriodEnabled = true,
        version = tosVersion,
        rollingAcceptanceWindowExpiration = rollingAcceptanceWindowExpiration,
        previousVersion = previousVersionOpt
      )
    )
    "Rolling acceptance window" - {
      "doesnt allow user to use the system if they haven't accepted the new version and there is no previous version" in {
        val tosVersion = "2"
        val previousTosVersion = None
        val tosService =
          new TosService(
            NoExtensions,
            dirDAO,
            TestSupport.tosConfig.copy(
              isTosEnabled = true,
              isGracePeriodEnabled = false,
              version = tosVersion,
              rollingAcceptanceWindowExpiration = rollingAcceptanceWindowExpiration,
              previousVersion = previousTosVersion
            )
          )

        when(dirDAO.getUserTermsOfService(defaultUser.id, samRequestContext))
          .thenReturn(IO.pure(None))

        when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousTosVersion, samRequestContext))
          .thenReturn(IO.pure(None))

        val complianceStatus = tosService.getTermsOfServiceComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
        complianceStatus.permitsSystemUsage shouldBe false
      }
    }

    // Note there is an assumption that the previous version of the ToS is always 1 version behind the current version
    /** | Case | Grace Period Enabled | Inside Acceptance Window | Accepted Version | Current Version | User accepted latest | Permits system usage |
      * |:-----|:---------------------|:-------------------------|:-----------------|:----------------|:---------------------|:---------------------|
      * | 1    | false                | false                    | null             | "2"             | false                | false                |
      * | 2    | false                | false                    | "1"              | "2"             | false                | false                |
      * | 3    | false                | false                    | "2"              | "2"             | true                 | true                 |
      * | 4    | true                 | flase                    | null             | "2"             | false                | false                |
      * | 5    | true                 | flase                    | "1"              | "2"             | false                | true                 |
      * | 6    | true                 | flase                    | "2"              | "2"             | true                 | true                 |
      * | 7    | false                | true                     | null             | "2"             | false                | false                |
      * | 8    | false                | true                     | "1"              | "2"             | false                | true                 |
      * | 9    | false                | true                     | "2"              | "2"             | true                 | true                 |
      * | 10   | true                 | true                     | null             | "2"             | false                | false                |
      * | 11   | true                 | true                     | "1"              | "2"             | false                | true                 |
      * | 12   | true                 | true                     | "2"              | "2"             | true                 | true                 |
      */

    "when the user has not accepted any ToS version" - {
      withoutGracePeriod - {
        withoutRollingAcceptanceWindow - {
          cannotUseTheSystem in {
            when(dirDAO.getUserTermsOfService(defaultUser.id, samRequestContext))
              .thenReturn(IO.pure(None))
            when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousVersionOpt, samRequestContext))
              .thenReturn(IO.pure(None))
            // CASE 1
            val complianceStatus =
              tosServiceV2GracePeriodDisabledAcceptanceWindowDisabled.getTermsOfServiceComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
            complianceStatus.permitsSystemUsage shouldBe false
          }
        }
      }
      withGracePeriod - {
        withoutRollingAcceptanceWindow - {
          cannotUseTheSystem in {
            when(dirDAO.getUserTermsOfService(defaultUser.id, samRequestContext))
              .thenReturn(IO.pure(None))
            when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousVersionOpt, samRequestContext))
              .thenReturn(IO.pure(None))
            // CASE 4
            val complianceStatus =
              tosServiceV2GracePeriodEnabledAcceptanceWindowDisabled.getTermsOfServiceComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
            complianceStatus.permitsSystemUsage shouldBe false
          }
        }
      }
      withoutGracePeriod - {
        withRollingAcceptanceWindow - {
          cannotUseTheSystem in {
            when(dirDAO.getUserTermsOfService(defaultUser.id, samRequestContext))
              .thenReturn(IO.pure(None))
            when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousVersionOpt, samRequestContext))
              .thenReturn(IO.pure(None))
            // CASE 7
            val complianceStatus =
              tosServiceV2GracePeriodDisabledAcceptanceWindowEnabled.getTermsOfServiceComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
            complianceStatus.permitsSystemUsage shouldBe false
          }
        }
      }
      withGracePeriod - {
        withRollingAcceptanceWindow - {
          cannotUseTheSystem in {
            when(dirDAO.getUserTermsOfService(defaultUser.id, samRequestContext))
              .thenReturn(IO.pure(None))
            when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousVersionOpt, samRequestContext))
              .thenReturn(IO.pure(None))
            // CASE 10
            val complianceStatus =
              tosServiceV2GracePeriodEnabledAcceptanceWindowEnabled.getTermsOfServiceComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
            complianceStatus.permitsSystemUsage shouldBe false
          }
        }
      }
    }
    "when the user has accepted the previous ToS version" - {
      withoutGracePeriod - {
        withoutRollingAcceptanceWindow - {
          cannotUseTheSystem in {
            when(dirDAO.getUserTermsOfService(defaultUser.id, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))
            when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousVersionOpt, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))
            // CASE 2
            val complianceStatus =
              tosServiceV2GracePeriodDisabledAcceptanceWindowDisabled.getTermsOfServiceComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
            complianceStatus.permitsSystemUsage shouldBe false
          }
        }
      }
      withGracePeriod - {
        withoutRollingAcceptanceWindow - {
          canUseTheSystem in {
            when(dirDAO.getUserTermsOfService(defaultUser.id, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))
            when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousVersionOpt, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))
            // CASE 5
            val complianceStatus =
              tosServiceV2GracePeriodEnabledAcceptanceWindowDisabled.getTermsOfServiceComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
            complianceStatus.permitsSystemUsage shouldBe true
          }
        }
      }
      withoutGracePeriod - {
        withRollingAcceptanceWindow - {
          canUseTheSystem in {
            when(dirDAO.getUserTermsOfService(defaultUser.id, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))
            when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousVersionOpt, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))
            // CASE 8
            val complianceStatus =
              tosServiceV2GracePeriodDisabledAcceptanceWindowEnabled.getTermsOfServiceComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
            complianceStatus.permitsSystemUsage shouldBe true
          }
        }
      }
      withGracePeriod - {
        withRollingAcceptanceWindow - {
          canUseTheSystem in {
            when(dirDAO.getUserTermsOfService(defaultUser.id, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))
            when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousVersionOpt, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))
            // CASE 11
            val complianceStatus =
              tosServiceV2GracePeriodEnabledAcceptanceWindowEnabled.getTermsOfServiceComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
            complianceStatus.permitsSystemUsage shouldBe true
          }
        }
      }
    }
    "when the user has accepted the current ToS version" - {
      withoutGracePeriod - {
        withoutRollingAcceptanceWindow - {
          canUseTheSystem in {
            when(dirDAO.getUserTermsOfService(defaultUser.id, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, tosVersion, TosTable.ACCEPT, Instant.now()))))
            when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousVersionOpt, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))
            // CASE 3
            val complianceStatus =
              tosServiceV2GracePeriodDisabledAcceptanceWindowDisabled.getTermsOfServiceComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
            complianceStatus.permitsSystemUsage shouldBe true
          }
        }
      }
      withGracePeriod - {
        withoutRollingAcceptanceWindow - {
          canUseTheSystem in {
            when(dirDAO.getUserTermsOfService(defaultUser.id, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, tosVersion, TosTable.ACCEPT, Instant.now()))))
            when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousVersionOpt, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))
            // CASE 6
            val complianceStatus =
              tosServiceV2GracePeriodEnabledAcceptanceWindowDisabled.getTermsOfServiceComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
            complianceStatus.permitsSystemUsage shouldBe true
          }
        }
      }
      withoutGracePeriod - {
        withRollingAcceptanceWindow - {
          canUseTheSystem in {
            when(dirDAO.getUserTermsOfService(defaultUser.id, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, tosVersion, TosTable.ACCEPT, Instant.now()))))
            when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousVersionOpt, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))
            // CASE 9
            val complianceStatus =
              tosServiceV2GracePeriodDisabledAcceptanceWindowEnabled.getTermsOfServiceComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
            complianceStatus.permitsSystemUsage shouldBe true
          }
        }
      }
      withGracePeriod - {
        withRollingAcceptanceWindow - {
          canUseTheSystem in {
            when(dirDAO.getUserTermsOfService(defaultUser.id, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, tosVersion, TosTable.ACCEPT, Instant.now()))))
            when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousVersionOpt, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))
            // CASE 12
            val complianceStatus =
              tosServiceV2GracePeriodEnabledAcceptanceWindowEnabled.getTermsOfServiceComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
            complianceStatus.permitsSystemUsage shouldBe true
          }
        }
      }
    }

    "when the user has rejected the latest ToS version" - {
      withoutGracePeriod - {
        withoutRollingAcceptanceWindow - {
          cannotUseTheSystem in {
            when(dirDAO.getUserTermsOfService(defaultUser.id, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, tosVersion, TosTable.REJECT, Instant.now()))))
            when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousVersionOpt, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))

            val complianceStatus =
              tosServiceV2GracePeriodDisabledAcceptanceWindowDisabled.getTermsOfServiceComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
            complianceStatus.permitsSystemUsage shouldBe false
          }
        }
      }
      withGracePeriod - {
        withoutRollingAcceptanceWindow - {
          cannotUseTheSystem in {
            when(dirDAO.getUserTermsOfService(defaultUser.id, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, tosVersion, TosTable.REJECT, Instant.now()))))
            when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousVersionOpt, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))

            val complianceStatus =
              tosServiceV2GracePeriodEnabledAcceptanceWindowDisabled.getTermsOfServiceComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
            complianceStatus.permitsSystemUsage shouldBe false
          }
        }
      }
      withoutGracePeriod - {
        withRollingAcceptanceWindow - {
          canUseTheSystem in {
            when(dirDAO.getUserTermsOfService(defaultUser.id, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, tosVersion, TosTable.REJECT, Instant.now()))))
            when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousVersionOpt, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))

            val complianceStatus =
              tosServiceV2GracePeriodDisabledAcceptanceWindowEnabled.getTermsOfServiceComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
            complianceStatus.permitsSystemUsage shouldBe false
          }
        }
      }
      withGracePeriod - {
        withRollingAcceptanceWindow - {
          cannotUseTheSystem in {
            when(dirDAO.getUserTermsOfService(defaultUser.id, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, tosVersion, TosTable.REJECT, Instant.now()))))
            when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousVersionOpt, samRequestContext))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))

            val complianceStatus =
              tosServiceV2GracePeriodEnabledAcceptanceWindowEnabled.getTermsOfServiceComplianceStatus(defaultUser, samRequestContext).unsafeRunSync()
            complianceStatus.permitsSystemUsage shouldBe false
          }
        }
      }
      // Checking that Sam does not say a user can use the system if they have rejected the latest ToS version
      withGracePeriod - {
        withRollingAcceptanceWindow - {
          cannotUseTheSystem + " according to their ToS details" in {
            val requestContextWithUser = samRequestContext.copy(samUser = Some(defaultUser))
            when(dirDAO.loadUser(defaultUser.id, requestContextWithUser))
              .thenReturn(IO.pure(Option(defaultUser)))
            when(dirDAO.getUserTermsOfService(defaultUser.id, requestContextWithUser))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, tosVersion, TosTable.REJECT, Instant.now()))))
            when(dirDAO.getUserTermsOfService(defaultUser.id, requestContextWithUser, Some(TosTable.ACCEPT)))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))
            when(dirDAO.getUserTermsOfServiceVersion(defaultUser.id, previousVersionOpt, requestContextWithUser))
              .thenReturn(IO.pure(Option(SamUserTos(defaultUser.id, previousVersion, TosTable.ACCEPT, Instant.now()))))

            val complianceStatus =
              tosServiceV2GracePeriodEnabledAcceptanceWindowEnabled.getTermsOfServiceDetailsForUser(defaultUser.id, requestContextWithUser).unsafeRunSync()
            complianceStatus.permitsSystemUsage shouldBe false
          }
        }
      }
    }

    "when a service account is using the api" - {
      "let it use the api regardless of tos status" in {
        when(dirDAO.getUserTermsOfService(serviceAccountUser.id, samRequestContext))
          .thenReturn(IO.pure(None))
        when(dirDAO.getUserTermsOfServiceVersion(serviceAccountUser.id, previousVersionOpt, samRequestContext))
          .thenReturn(IO.pure(None))
        val complianceStatus =
          tosServiceV2GracePeriodDisabledAcceptanceWindowDisabled.getTermsOfServiceComplianceStatus(serviceAccountUser, samRequestContext).unsafeRunSync()
        complianceStatus.permitsSystemUsage shouldBe true
      }
    }

    "can retrieve Terms of Service details for a user" - {
      "if the requesting user is an admin" in {
        // Arrange
        val tosVersion = "0"
        val adminUser = Generator.genWorkbenchUserBoth.sample.get
        val directoryDao = new MockDirectoryDaoBuilder()
          .withAcceptedTermsOfServiceForUser(defaultUser, tosVersion)
          .build

        val cloudExt = MockCloudExtensionsBuilder(allUsersGroup).withAdminUser().build

        val tosService = new TosService(cloudExt, directoryDao, TestSupport.tosConfig)

        // Act
        val userTosDetails: TermsOfServiceDetails =
          runAndWait(tosService.getTermsOfServiceDetailsForUser(defaultUser.id, SamRequestContext(None, None, Some(adminUser))))

        // Assert
        userTosDetails should have {
          latestAcceptedVersion(tosVersion)
          acceptedOn(Instant.now)
          permitsSystemUsage(true)
        }
      }

      "if the requesting user is not an admin but is the same as the requested user" in {
        // Arrange
        val tosVersion = "0"
        val directoryDao = new MockDirectoryDaoBuilder()
          .withAcceptedTermsOfServiceForUser(defaultUser, tosVersion)
          .build

        val cloudExt = MockCloudExtensionsBuilder(allUsersGroup).withNonAdminUser().build

        val tosService = new TosService(cloudExt, directoryDao, TestSupport.tosConfig)

        // Act
        val userTosDetails: TermsOfServiceDetails =
          runAndWait(tosService.getTermsOfServiceDetailsForUser(defaultUser.id, SamRequestContext(None, None, Some(defaultUser))))

        // Assert
        userTosDetails should have {
          latestAcceptedVersion(tosVersion)
          acceptedOn(Instant.now)
          permitsSystemUsage(true)
        }
      }
    }

    "cannot retrieve Terms of Service details for another user" - {
      "if requesting user is not an admin and the requested user is a different user" in {
        // Arrange
        val tosVersion = "v1"
        val nonAdminUser = Generator.genWorkbenchUserBoth.sample.get
        val someRandoUser = Generator.genWorkbenchUserBoth.sample.get
        val directoryDao = new MockDirectoryDaoBuilder()
          .withAcceptedTermsOfServiceForUser(someRandoUser, tosVersion)
          .build
        val cloudExt = MockCloudExtensionsBuilder(allUsersGroup).withNonAdminUser().build

        val tosService = new TosService(cloudExt, directoryDao, TestSupport.tosConfig)

        // Act and Assert
        val e = intercept[WorkbenchExceptionWithErrorReport] {
          runAndWait(tosService.getTermsOfServiceDetailsForUser(someRandoUser.id, SamRequestContext(None, None, Some(nonAdminUser))))
        }

        assert(e.errorReport.statusCode.value == StatusCodes.Unauthorized, "User should not be authorized to see other users' Terms of Service details")
      }
    }

    "can retrieve Terms of Service history for a user" - {
      "if the requesting user is an admin" in {
        // Arrange
        val tosVersion = "0"
        val adminUser = Generator.genWorkbenchUserBoth.sample.get
        val record1 = SamUserTos(adminUser.id, tosVersion, TosTable.ACCEPT, Instant.now())
        val record2 = SamUserTos(adminUser.id, tosVersion, TosTable.REJECT, Instant.now().minusSeconds(5))
        val directoryDao = new MockDirectoryDaoBuilder()
          .withTermsOfServiceHistoryForUser(defaultUser, List(record1, record2))
          .build
        val cloudExt = MockCloudExtensionsBuilder(allUsersGroup).withAdminUser().build

        val tosService = new TosService(cloudExt, directoryDao, TestSupport.tosConfig)

        // Act
        val userTosDetails: TermsOfServiceHistory =
          runAndWait(tosService.getTermsOfServiceHistoryForUser(defaultUser.id, SamRequestContext(None, None, Some(adminUser)), 5))

        // Assert
        userTosDetails.history.size shouldBe 2
        userTosDetails.history.head shouldBe record1.toHistoryRecord
        userTosDetails.history.last shouldBe record2.toHistoryRecord
      }

      "if the requesting user is not an admin but is the same as the requested user" in {
        // Arrange
        val tosVersion = "0"
        val userTos1 = SamUserTos(defaultUser.id, tosVersion, TosTable.ACCEPT, Instant.now())
        val userTos2 = SamUserTos(defaultUser.id, tosVersion, TosTable.REJECT, Instant.now().minusSeconds(5))
        val directoryDao = new MockDirectoryDaoBuilder()
          .withTermsOfServiceHistoryForUser(defaultUser, List(userTos1, userTos2))
          .build

        val cloudExt = MockCloudExtensionsBuilder(allUsersGroup).withNonAdminUser().build

        val tosService = new TosService(cloudExt, directoryDao, TestSupport.tosConfig)

        // Act
        val userTosDetails: TermsOfServiceHistory =
          runAndWait(tosService.getTermsOfServiceHistoryForUser(defaultUser.id, SamRequestContext(None, None, Some(defaultUser)), 5))

        // Assert
        userTosDetails.history.size shouldBe 2
        userTosDetails.history.head shouldBe userTos1.toHistoryRecord
        userTosDetails.history.last shouldBe userTos2.toHistoryRecord
      }
    }
    "cannot retrieve Terms of Service history for another user" - {
      "if requesting user is not an admin and the requested user is a different user" in {
        // Arrange
        val tosVersion = "v1"
        val nonAdminUser = Generator.genWorkbenchUserBoth.sample.get
        val someRandoUser = Generator.genWorkbenchUserBoth.sample.get
        val userTos1 = SamUserTos(someRandoUser.id, tosVersion, TosTable.ACCEPT, Instant.now())
        val userTos2 = SamUserTos(someRandoUser.id, tosVersion, TosTable.REJECT, Instant.now().minusSeconds(5))
        val directoryDao = new MockDirectoryDaoBuilder()
          .withTermsOfServiceHistoryForUser(someRandoUser, List(userTos1, userTos2))
          .build
        val cloudExt = MockCloudExtensionsBuilder(allUsersGroup).withNonAdminUser().build

        val tosService = new TosService(cloudExt, directoryDao, TestSupport.tosConfig)

        // Act and Assert
        val e = intercept[WorkbenchExceptionWithErrorReport] {
          runAndWait(tosService.getTermsOfServiceHistoryForUser(someRandoUser.id, SamRequestContext(None, None, Some(nonAdminUser)), 5))
        }

        assert(e.errorReport.statusCode.value == StatusCodes.Unauthorized, "User should not be authorized to see other users' Terms of Service details")
      }
    }
  }
}
