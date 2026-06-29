package org.broadinstitute.dsde.workbench.sam.google

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.google.api.services.groupssettings.model.{Groups => GroupSettings}
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.ResourceTypeName
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.invocation.InvocationOnMock
import org.mockito.scalatest.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration._

class GoogleGroupExternalMembersMigratorSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val samRequestContext = SamRequestContext()

  private def enabledUser(id: String): SamUser =
    SamUser(WorkbenchUserId(id), None, WorkbenchEmail(s"$id@example.com"), None, enabled = true, Instant.EPOCH, None, Instant.EPOCH)

  private def newMigrator(directoryDAO: DirectoryDAO, googleExtensions: GoogleExtensions): GoogleGroupExternalMembersMigrator =
    new GoogleGroupExternalMembersMigrator(directoryDAO, googleExtensions, throttleDelay = 1.millisecond, pageSize = 200)

  "migrating the proxy tier" should "enable external members on and re-add each enabled user to their proxy group" in {
    val user1 = enabledUser("user1")
    val user2 = enabledUser("user2")

    val directoryDAO = mock[DirectoryDAO]
    when(directoryDAO.countEnabledUsers(any[SamRequestContext])).thenReturn(IO.pure(2L))
    when(directoryDAO.loadEnabledUsers(any[Option[WorkbenchUserId]], any[Int], any[SamRequestContext]))
      .thenReturn(IO.pure(Seq(user1, user2)), IO.pure(Seq.empty))

    val googleDirectoryDAO = mock[GoogleDirectoryDAO]
    when(googleDirectoryDAO.enableExternalMembersIfNeeded(any[WorkbenchEmail])).thenReturn(Future.successful(new GroupSettings))

    val googleExtensions = mock[GoogleExtensions]
    when(googleExtensions.googleDirectoryDAO).thenReturn(googleDirectoryDAO)
    when(googleExtensions.toProxyFromUser(any[WorkbenchUserId]))
      .thenAnswer((invocation: InvocationOnMock) => WorkbenchEmail(s"PROXY_${invocation.getArgument[WorkbenchUserId](0).value}@example.com"))
    when(googleExtensions.onUserEnable(any[SamUser], any[SamRequestContext])).thenReturn(IO.unit)

    val summary = newMigrator(directoryDAO, googleExtensions).migrate(MigrationTier.Proxy, after = None, samRequestContext).unsafeRunSync()

    summary shouldBe GroupExternalMembersMigrationSummary(processed = 2, failed = 0)
    verify(googleDirectoryDAO).enableExternalMembersIfNeeded(WorkbenchEmail("PROXY_user1@example.com"))
    verify(googleDirectoryDAO).enableExternalMembersIfNeeded(WorkbenchEmail("PROXY_user2@example.com"))
    verify(googleExtensions).onUserEnable(user1, samRequestContext)
    verify(googleExtensions).onUserEnable(user2, samRequestContext)
  }

  it should "resume after the given cursor" in {
    val directoryDAO = mock[DirectoryDAO]
    when(directoryDAO.countEnabledUsers(any[SamRequestContext])).thenReturn(IO.pure(0L))
    when(directoryDAO.loadEnabledUsers(any[Option[WorkbenchUserId]], any[Int], any[SamRequestContext])).thenReturn(IO.pure(Seq.empty))

    // empty result set, so no Google calls are made
    val googleExtensions = mock[GoogleExtensions]

    newMigrator(directoryDAO, googleExtensions).migrate(MigrationTier.Proxy, after = Some("user1"), samRequestContext).unsafeRunSync()

    // the first page is fetched starting after the resume cursor
    verify(directoryDAO).loadEnabledUsers(ArgumentMatchers.eq(Some(WorkbenchUserId("user1"))), any[Int], any[SamRequestContext])
  }

  "migrating a resource type tier" should "enable external members on each synced group without re-adding members" in {
    val resourceTypeName = ResourceTypeName("managed-group")
    val group1 = WorkbenchEmail("group1@example.com")
    val group2 = WorkbenchEmail("group2@example.com")

    val directoryDAO = mock[DirectoryDAO]
    when(
      directoryDAO.loadSynchronizedGroupEmailsByResourceType(
        ArgumentMatchers.eq(resourceTypeName),
        any[Option[WorkbenchEmail]],
        any[Int],
        any[SamRequestContext]
      )
    ).thenReturn(IO.pure(Seq(group1, group2)), IO.pure(Seq.empty))
    when(directoryDAO.countSynchronizedGroupsByResourceType(ArgumentMatchers.eq(resourceTypeName), any[SamRequestContext])).thenReturn(IO.pure(2L))

    val googleDirectoryDAO = mock[GoogleDirectoryDAO]
    when(googleDirectoryDAO.enableExternalMembersIfNeeded(any[WorkbenchEmail])).thenReturn(Future.successful(new GroupSettings))

    val googleExtensions = mock[GoogleExtensions]
    when(googleExtensions.googleDirectoryDAO).thenReturn(googleDirectoryDAO)

    val summary =
      newMigrator(directoryDAO, googleExtensions).migrate(MigrationTier.ResourceType(resourceTypeName), after = None, samRequestContext).unsafeRunSync()

    summary shouldBe GroupExternalMembersMigrationSummary(processed = 2, failed = 0)
    verify(googleDirectoryDAO).enableExternalMembersIfNeeded(group1)
    verify(googleDirectoryDAO).enableExternalMembersIfNeeded(group2)
    verify(googleExtensions, never).onUserEnable(any[SamUser], any[SamRequestContext])
  }

  it should "count failures and keep processing the rest" in {
    val resourceTypeName = ResourceTypeName("managed-group")
    val badGroup = WorkbenchEmail("bad@example.com")
    val goodGroup = WorkbenchEmail("good@example.com")

    val directoryDAO = mock[DirectoryDAO]
    when(
      directoryDAO.loadSynchronizedGroupEmailsByResourceType(
        ArgumentMatchers.eq(resourceTypeName),
        any[Option[WorkbenchEmail]],
        any[Int],
        any[SamRequestContext]
      )
    ).thenReturn(IO.pure(Seq(badGroup, goodGroup)), IO.pure(Seq.empty))
    when(directoryDAO.countSynchronizedGroupsByResourceType(ArgumentMatchers.eq(resourceTypeName), any[SamRequestContext])).thenReturn(IO.pure(2L))

    val googleDirectoryDAO = mock[GoogleDirectoryDAO]
    when(googleDirectoryDAO.enableExternalMembersIfNeeded(badGroup)).thenReturn(Future.failed(new RuntimeException("boom")))
    when(googleDirectoryDAO.enableExternalMembersIfNeeded(goodGroup)).thenReturn(Future.successful(new GroupSettings))

    val googleExtensions = mock[GoogleExtensions]
    when(googleExtensions.googleDirectoryDAO).thenReturn(googleDirectoryDAO)

    val summary =
      newMigrator(directoryDAO, googleExtensions).migrate(MigrationTier.ResourceType(resourceTypeName), after = None, samRequestContext).unsafeRunSync()

    summary shouldBe GroupExternalMembersMigrationSummary(processed = 2, failed = 1)
    verify(googleDirectoryDAO).enableExternalMembersIfNeeded(goodGroup)
  }

  "MigrationTier.fromSelector" should "parse the proxy tier and resource type tiers" in {
    MigrationTier.fromSelector("proxy") shouldBe MigrationTier.Proxy
    MigrationTier.fromSelector("managed-group") shouldBe MigrationTier.ResourceType(ResourceTypeName("managed-group"))
  }
}
