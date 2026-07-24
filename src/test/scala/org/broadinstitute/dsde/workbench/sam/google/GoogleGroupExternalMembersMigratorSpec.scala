package org.broadinstitute.dsde.workbench.sam.google

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.google.api.services.groupssettings.model.{Groups => GroupSettings}
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, ExternalMembersMigrationRecord, MigrationState}
import org.broadinstitute.dsde.workbench.sam.model.ResourceTypeName
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.lenient
import org.mockito.invocation.InvocationOnMock
import org.mockito.scalatest.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.Future

class GoogleGroupExternalMembersMigratorSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val samRequestContext = SamRequestContext()

  private def enabledUser(id: String): SamUser =
    SamUser(WorkbenchUserId(id), None, WorkbenchEmail(s"$id@example.com"), None, enabled = true, Instant.EPOCH, None, Instant.EPOCH)

  private def migrationRecord(tier: String, state: String, lastCursor: Option[String]): ExternalMembersMigrationRecord =
    ExternalMembersMigrationRecord(tier, state, None, 0, 0, lastCursor, Instant.EPOCH, Instant.EPOCH)

  // stub the status bookkeeping the migrator does for every tier; `existing` is what getExternalMembersMigration returns.
  // stubbed leniently because not every test exercises every bookkeeping call (e.g. a skipped tier never records progress).
  // the count stubs default to 0; tests that assert a specific total override them.
  private def stubStatus(dao: DirectoryDAO, existing: Option[ExternalMembersMigrationRecord] = None): Unit = {
    lenient().when(dao.getExternalMembersMigration(any[String], any[SamRequestContext])).thenReturn(IO.pure(existing))
    lenient()
      .when(dao.recordExternalMembersMigration(any[String], any[String], any[Option[Long]], any[Long], any[Long], any[Option[String]], any[SamRequestContext]))
      .thenReturn(IO.unit)
    lenient().when(dao.setExternalMembersMigrationState(any[String], any[String], any[SamRequestContext])).thenReturn(IO.unit)
    lenient().when(dao.countEnabledUsers(any[SamRequestContext])).thenReturn(IO.pure(0L))
    lenient().when(dao.countSynchronizedGroupEmailsByResourceType(any[ResourceTypeName], any[SamRequestContext])).thenReturn(IO.pure(0L))
  }

  private def newMigrator(directoryDAO: DirectoryDAO, googleExtensions: GoogleExtensions): GoogleGroupExternalMembersMigrator =
    new GoogleGroupExternalMembersMigrator(directoryDAO, googleExtensions, defaultQueriesPerMinute = 120000)

  "migrating the proxy tier" should "enable external members on and re-add each enabled user's email to their proxy group" in {
    val user1 = enabledUser("user1")
    val user2 = enabledUser("user2")

    val directoryDAO = mock[DirectoryDAO]
    stubStatus(directoryDAO)
    when(directoryDAO.countEnabledUsers(any[SamRequestContext])).thenReturn(IO.pure(2L))
    // first page returns both users, the next page is empty so pagination terminates
    when(directoryDAO.loadEnabledUsers(any[Option[WorkbenchUserId]], any[Int], any[SamRequestContext]))
      .thenReturn(IO.pure(Seq(user1, user2)), IO.pure(Seq.empty))

    val googleDirectoryDAO = mock[GoogleDirectoryDAO]
    when(googleDirectoryDAO.enableExternalMembersIfNeeded(any[WorkbenchEmail])).thenReturn(Future.successful(new GroupSettings))
    when(googleDirectoryDAO.addMemberToGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))

    val googleExtensions = mock[GoogleExtensions]
    when(googleExtensions.googleDirectoryDAO).thenReturn(googleDirectoryDAO)
    when(googleExtensions.toProxyFromUser(any[WorkbenchUserId]))
      .thenAnswer((invocation: InvocationOnMock) => WorkbenchEmail(s"PROXY_${invocation.getArgument[WorkbenchUserId](0).value}@example.com"))

    newMigrator(directoryDAO, googleExtensions).migrate(List(MigrationTier.Proxy), samRequestContext).unsafeRunSync()

    verify(googleDirectoryDAO).enableExternalMembersIfNeeded(WorkbenchEmail("PROXY_user1@example.com"))
    verify(googleDirectoryDAO).enableExternalMembersIfNeeded(WorkbenchEmail("PROXY_user2@example.com"))
    // each user's own email is re-added to their proxy group; pet service accounts are left untouched
    verify(googleDirectoryDAO).addMemberToGroup(WorkbenchEmail("PROXY_user1@example.com"), user1.email)
    verify(googleDirectoryDAO).addMemberToGroup(WorkbenchEmail("PROXY_user2@example.com"), user2.email)
    // the tier is recorded completed with the final counts
    verify(directoryDAO).recordExternalMembersMigration(
      ArgumentMatchers.eq("proxy"),
      ArgumentMatchers.eq(MigrationState.Completed),
      ArgumentMatchers.eq(Option(2L)),
      ArgumentMatchers.eq(2L),
      ArgumentMatchers.eq(0L),
      any[Option[String]],
      any[SamRequestContext]
    )
  }

  it should "resume from the last recorded cursor of a prior run" in {
    val directoryDAO = mock[DirectoryDAO]
    stubStatus(directoryDAO, existing = Some(migrationRecord("proxy", MigrationState.Running, lastCursor = Some("user1"))))
    when(directoryDAO.loadEnabledUsers(any[Option[WorkbenchUserId]], any[Int], any[SamRequestContext])).thenReturn(IO.pure(Seq.empty))

    // empty result set, so no Google calls are made
    val googleExtensions = mock[GoogleExtensions]

    newMigrator(directoryDAO, googleExtensions).migrate(List(MigrationTier.Proxy), samRequestContext).unsafeRunSync()

    // users are loaded starting after the recorded cursor
    verify(directoryDAO).loadEnabledUsers(ArgumentMatchers.eq(Some(WorkbenchUserId("user1"))), any[Int], any[SamRequestContext])
  }

  "migrating a resource type tier" should "enable external members on each synced group without re-adding members" in {
    val resourceTypeName = ResourceTypeName("managed-group")
    val group1 = WorkbenchEmail("group1@example.com")
    val group2 = WorkbenchEmail("group2@example.com")

    val directoryDAO = mock[DirectoryDAO]
    stubStatus(directoryDAO)
    when(directoryDAO.countSynchronizedGroupEmailsByResourceType(ArgumentMatchers.eq(resourceTypeName), any[SamRequestContext])).thenReturn(IO.pure(2L))
    when(
      directoryDAO.loadSynchronizedGroupEmailsByResourceType(
        ArgumentMatchers.eq(resourceTypeName),
        any[Option[WorkbenchEmail]],
        any[Int],
        any[SamRequestContext]
      )
    ).thenReturn(IO.pure(Seq(group1, group2)), IO.pure(Seq.empty))

    val googleDirectoryDAO = mock[GoogleDirectoryDAO]
    when(googleDirectoryDAO.enableExternalMembersIfNeeded(any[WorkbenchEmail])).thenReturn(Future.successful(new GroupSettings))

    val googleExtensions = mock[GoogleExtensions]
    when(googleExtensions.googleDirectoryDAO).thenReturn(googleDirectoryDAO)

    newMigrator(directoryDAO, googleExtensions).migrate(List(MigrationTier.ResourceType(resourceTypeName)), samRequestContext).unsafeRunSync()

    verify(googleDirectoryDAO).enableExternalMembersIfNeeded(group1)
    verify(googleDirectoryDAO).enableExternalMembersIfNeeded(group2)
    // resource-type groups only flip the setting; no member is re-added
    verify(googleDirectoryDAO, never).addMemberToGroup(any[WorkbenchEmail], any[WorkbenchEmail])
  }

  it should "count failures and keep processing the rest" in {
    val resourceTypeName = ResourceTypeName("managed-group")
    val badGroup = WorkbenchEmail("bad@example.com")
    val goodGroup = WorkbenchEmail("good@example.com")

    val directoryDAO = mock[DirectoryDAO]
    stubStatus(directoryDAO)
    when(directoryDAO.countSynchronizedGroupEmailsByResourceType(ArgumentMatchers.eq(resourceTypeName), any[SamRequestContext])).thenReturn(IO.pure(2L))
    when(
      directoryDAO.loadSynchronizedGroupEmailsByResourceType(
        ArgumentMatchers.eq(resourceTypeName),
        any[Option[WorkbenchEmail]],
        any[Int],
        any[SamRequestContext]
      )
    ).thenReturn(IO.pure(Seq(badGroup, goodGroup)), IO.pure(Seq.empty))

    val googleDirectoryDAO = mock[GoogleDirectoryDAO]
    when(googleDirectoryDAO.enableExternalMembersIfNeeded(badGroup)).thenReturn(Future.failed(new RuntimeException("boom")))
    when(googleDirectoryDAO.enableExternalMembersIfNeeded(goodGroup)).thenReturn(Future.successful(new GroupSettings))

    val googleExtensions = mock[GoogleExtensions]
    when(googleExtensions.googleDirectoryDAO).thenReturn(googleDirectoryDAO)

    newMigrator(directoryDAO, googleExtensions).migrate(List(MigrationTier.ResourceType(resourceTypeName)), samRequestContext).unsafeRunSync()

    verify(googleDirectoryDAO).enableExternalMembersIfNeeded(goodGroup)
    // both processed, one counted as failed
    verify(directoryDAO).recordExternalMembersMigration(
      ArgumentMatchers.eq("managed-group"),
      ArgumentMatchers.eq(MigrationState.Completed),
      ArgumentMatchers.eq(Option(2L)),
      ArgumentMatchers.eq(2L),
      ArgumentMatchers.eq(1L),
      any[Option[String]],
      any[SamRequestContext]
    )
  }

  it should "pace Google calls according to the queriesPerMinute override" in {
    val resourceTypeName = ResourceTypeName("managed-group")
    val groups = (1 to 4).map(i => WorkbenchEmail(s"group$i@example.com"))

    val directoryDAO = mock[DirectoryDAO]
    stubStatus(directoryDAO)
    when(directoryDAO.countSynchronizedGroupEmailsByResourceType(ArgumentMatchers.eq(resourceTypeName), any[SamRequestContext])).thenReturn(IO.pure(4L))
    when(
      directoryDAO.loadSynchronizedGroupEmailsByResourceType(
        ArgumentMatchers.eq(resourceTypeName),
        any[Option[WorkbenchEmail]],
        any[Int],
        any[SamRequestContext]
      )
    ).thenReturn(IO.pure(groups), IO.pure(Seq.empty))

    val googleDirectoryDAO = mock[GoogleDirectoryDAO]
    when(googleDirectoryDAO.enableExternalMembersIfNeeded(any[WorkbenchEmail])).thenReturn(Future.successful(new GroupSettings))

    val googleExtensions = mock[GoogleExtensions]
    when(googleExtensions.googleDirectoryDAO).thenReturn(googleDirectoryDAO)

    // 1200 queries/min => ~2 queries per group => a 100ms interval between the 4 groups, so >= 3 intervals of spacing before the run completes
    val start = System.nanoTime()
    new GoogleGroupExternalMembersMigrator(directoryDAO, googleExtensions)
      .migrate(List(MigrationTier.ResourceType(resourceTypeName)), samRequestContext, queriesPerMinuteOverride = Some(1200))
      .unsafeRunSync()
    val elapsedMillis = (System.nanoTime() - start) / 1000000

    elapsedMillis should be >= 250L
    verify(googleDirectoryDAO, times(4)).enableExternalMembersIfNeeded(any[WorkbenchEmail])
  }

  "migrating multiple tiers" should "skip tiers already completed and run the rest in order" in {
    val directoryDAO = mock[DirectoryDAO]
    when(directoryDAO.getExternalMembersMigration(ArgumentMatchers.eq("proxy"), any[SamRequestContext]))
      .thenReturn(IO.pure(Some(migrationRecord("proxy", MigrationState.Completed, lastCursor = Some("user9")))))
    when(directoryDAO.getExternalMembersMigration(ArgumentMatchers.eq("managed-group"), any[SamRequestContext])).thenReturn(IO.pure(None))
    when(
      directoryDAO.recordExternalMembersMigration(
        any[String],
        any[String],
        any[Option[Long]],
        any[Long],
        any[Long],
        any[Option[String]],
        any[SamRequestContext]
      )
    )
      .thenReturn(IO.unit)
    when(directoryDAO.countSynchronizedGroupEmailsByResourceType(any[ResourceTypeName], any[SamRequestContext])).thenReturn(IO.pure(0L))
    when(directoryDAO.loadSynchronizedGroupEmailsByResourceType(any[ResourceTypeName], any[Option[WorkbenchEmail]], any[Int], any[SamRequestContext]))
      .thenReturn(IO.pure(Seq.empty))

    val googleExtensions = mock[GoogleExtensions]

    newMigrator(directoryDAO, googleExtensions)
      .migrate(List(MigrationTier.Proxy, MigrationTier.ResourceType(ResourceTypeName("managed-group"))), samRequestContext)
      .unsafeRunSync()

    // proxy is already completed, so it never records progress; the other tier runs and records completion
    verify(directoryDAO, never).recordExternalMembersMigration(
      ArgumentMatchers.eq("proxy"),
      any[String],
      any[Option[Long]],
      any[Long],
      any[Long],
      any[Option[String]],
      any[SamRequestContext]
    )
    verify(directoryDAO).recordExternalMembersMigration(
      ArgumentMatchers.eq("managed-group"),
      ArgumentMatchers.eq(MigrationState.Completed),
      any[Option[Long]],
      any[Long],
      any[Long],
      any[Option[String]],
      any[SamRequestContext]
    )
  }

  "status" should "return the persisted migration records" in {
    val records = Seq(migrationRecord("proxy", MigrationState.Completed, None), migrationRecord("managed-group", MigrationState.Running, Some("g")))
    val directoryDAO = mock[DirectoryDAO]
    when(directoryDAO.listExternalMembersMigrations(any[SamRequestContext])).thenReturn(IO.pure(records))

    newMigrator(directoryDAO, mock[GoogleExtensions]).status(samRequestContext).unsafeRunSync() shouldBe records
  }

  "MigrationTier.fromSelector" should "parse the proxy tier and known resource type tiers, rejecting unknown selectors" in {
    val validResourceTypes = Set("managed-group", "workspace")
    MigrationTier.fromSelector("proxy", validResourceTypes) shouldBe Some(MigrationTier.Proxy)
    MigrationTier.fromSelector("managed-group", validResourceTypes) shouldBe Some(MigrationTier.ResourceType(ResourceTypeName("managed-group")))
    // an unknown / typo'd resource type is rejected rather than silently accepted
    MigrationTier.fromSelector("managed-grup", validResourceTypes) shouldBe None
  }
}
