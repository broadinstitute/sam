package org.broadinstitute.dsde.workbench.sam.google

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.broadinstitute.dsde.workbench.google.GoogleDirectoryDAO
import org.broadinstitute.dsde.workbench.model.{WorkbenchEmail, WorkbenchGroupIdentity, WorkbenchGroupName, WorkbenchSubject, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AllUsersCleanupRecord, DirectoryDAO, MigrationState}
import org.broadinstitute.dsde.workbench.sam.model.BasicWorkbenchGroup
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.lenient
import org.mockito.invocation.InvocationOnMock
import org.mockito.scalatest.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.{global => ecGlobal}
import scala.concurrent.Future

class AllUsersGroupCleanupMigratorSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  private val samRequestContext = SamRequestContext()
  private val allUsersGroup = BasicWorkbenchGroup(WorkbenchGroupName("All_Users"), Set.empty, WorkbenchEmail("GROUP_All_Users@example.com"))

  private def testUser(id: String): SamUser =
    SamUser(WorkbenchUserId(id), None, WorkbenchEmail(s"$id@example.com"), None, enabled = true, Instant.EPOCH, None, Instant.EPOCH)

  private def cleanupRecord(emailPattern: String, state: String, lastCursor: Option[String]): AllUsersCleanupRecord =
    AllUsersCleanupRecord(emailPattern, state, None, 0, 0, lastCursor, Instant.EPOCH, Instant.EPOCH)

  // stub the status bookkeeping the migrator does for every run; `existing` is what getAllUsersCleanupRun returns.
  // stubbed leniently because not every test exercises every bookkeeping call.
  private def stubStatus(dao: DirectoryDAO, googleExtensions: GoogleExtensions, existing: Option[AllUsersCleanupRecord] = None): Unit = {
    lenient().when(dao.getAllUsersCleanupRun(any[String], any[SamRequestContext])).thenReturn(IO.pure(existing))
    lenient()
      .when(dao.recordAllUsersCleanupProgress(any[String], any[String], any[Option[Long]], any[Long], any[Long], any[Option[String]], any[SamRequestContext]))
      .thenReturn(IO.unit)
    lenient().when(dao.setAllUsersCleanupState(any[String], any[String], any[SamRequestContext])).thenReturn(IO.unit)
    lenient().when(dao.countUsersByEmailPattern(any[String], any[SamRequestContext])).thenReturn(IO.pure(0L))
    lenient()
      .when(googleExtensions.getOrCreateAllUsersGroup(any[DirectoryDAO], any[SamRequestContext])(any[ExecutionContext]))
      .thenReturn(IO.pure(allUsersGroup))
  }

  private def newMigrator(directoryDAO: DirectoryDAO, googleExtensions: GoogleExtensions): AllUsersGroupCleanupMigrator =
    new AllUsersGroupCleanupMigrator(directoryDAO, googleExtensions, defaultQueriesPerMinute = 120000)

  "removeMatching" should "remove each matching user from All_Users in both Postgres and Google" in {
    val user1 = testUser("user1")
    val user2 = testUser("user2")
    val pattern = "tdr-ingest-sa@datarepo-%"

    val directoryDAO = mock[DirectoryDAO]
    val googleExtensions = mock[GoogleExtensions]
    stubStatus(directoryDAO, googleExtensions)
    when(directoryDAO.countUsersByEmailPattern(ArgumentMatchers.eq(pattern), any[SamRequestContext])).thenReturn(IO.pure(2L))
    // first page returns both users, the next page is empty so pagination terminates
    when(directoryDAO.loadUsersByEmailPattern(ArgumentMatchers.eq(pattern), any[Option[WorkbenchUserId]], any[Int], any[SamRequestContext]))
      .thenReturn(IO.pure(Seq(user1, user2)), IO.pure(Seq.empty))
    when(directoryDAO.removeGroupMember(any[WorkbenchGroupIdentity], any[WorkbenchSubject], any[SamRequestContext]))
      .thenReturn(IO.pure(true))

    val googleDirectoryDAO = mock[GoogleDirectoryDAO]
    when(googleDirectoryDAO.removeMemberFromGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))
    when(googleExtensions.googleDirectoryDAO).thenReturn(googleDirectoryDAO)
    when(googleExtensions.toProxyFromUser(any[WorkbenchUserId]))
      .thenAnswer((invocation: InvocationOnMock) => WorkbenchEmail(s"PROXY_${invocation.getArgument[WorkbenchUserId](0).value}@example.com"))

    newMigrator(directoryDAO, googleExtensions).removeMatching(pattern, samRequestContext).unsafeRunSync()

    verify(directoryDAO).removeGroupMember(allUsersGroup.id, user1.id, samRequestContext)
    verify(directoryDAO).removeGroupMember(allUsersGroup.id, user2.id, samRequestContext)
    verify(googleDirectoryDAO).removeMemberFromGroup(allUsersGroup.email, WorkbenchEmail("PROXY_user1@example.com"))
    verify(googleDirectoryDAO).removeMemberFromGroup(allUsersGroup.email, WorkbenchEmail("PROXY_user2@example.com"))
    // the run is recorded completed with the final counts
    verify(directoryDAO).recordAllUsersCleanupProgress(
      ArgumentMatchers.eq(pattern),
      ArgumentMatchers.eq(MigrationState.Completed),
      ArgumentMatchers.eq(Option(2L)),
      ArgumentMatchers.eq(2L),
      ArgumentMatchers.eq(0L),
      any[Option[String]],
      any[SamRequestContext]
    )
  }

  it should "resume from the last recorded cursor of a prior run" in {
    val pattern = "tdr-ingest-sa@datarepo-%"
    val directoryDAO = mock[DirectoryDAO]
    val googleExtensions = mock[GoogleExtensions]
    stubStatus(directoryDAO, googleExtensions, existing = Some(cleanupRecord(pattern, MigrationState.Running, lastCursor = Some("user1"))))
    when(directoryDAO.loadUsersByEmailPattern(ArgumentMatchers.eq(pattern), any[Option[WorkbenchUserId]], any[Int], any[SamRequestContext]))
      .thenReturn(IO.pure(Seq.empty))

    newMigrator(directoryDAO, googleExtensions).removeMatching(pattern, samRequestContext).unsafeRunSync()

    // users are loaded starting after the recorded cursor
    verify(directoryDAO).loadUsersByEmailPattern(
      ArgumentMatchers.eq(pattern),
      ArgumentMatchers.eq(Some(WorkbenchUserId("user1"))),
      any[Int],
      any[SamRequestContext]
    )
  }

  it should "count failures and keep processing the rest" in {
    val badUser = testUser("bad-user")
    val goodUser = testUser("good-user")
    val pattern = "tdr-ingest-sa@datarepo-%"

    val directoryDAO = mock[DirectoryDAO]
    val googleExtensions = mock[GoogleExtensions]
    stubStatus(directoryDAO, googleExtensions)
    when(directoryDAO.countUsersByEmailPattern(ArgumentMatchers.eq(pattern), any[SamRequestContext])).thenReturn(IO.pure(2L))
    when(directoryDAO.loadUsersByEmailPattern(ArgumentMatchers.eq(pattern), any[Option[WorkbenchUserId]], any[Int], any[SamRequestContext]))
      .thenReturn(IO.pure(Seq(badUser, goodUser)), IO.pure(Seq.empty))
    when(directoryDAO.removeGroupMember(any[WorkbenchGroupIdentity], ArgumentMatchers.eq(badUser.id), any[SamRequestContext]))
      .thenReturn(IO.raiseError(new RuntimeException("boom")))
    when(directoryDAO.removeGroupMember(any[WorkbenchGroupIdentity], ArgumentMatchers.eq(goodUser.id), any[SamRequestContext]))
      .thenReturn(IO.pure(true))

    val googleDirectoryDAO = mock[GoogleDirectoryDAO]
    when(googleDirectoryDAO.removeMemberFromGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))
    when(googleExtensions.googleDirectoryDAO).thenReturn(googleDirectoryDAO)
    when(googleExtensions.toProxyFromUser(any[WorkbenchUserId]))
      .thenAnswer((invocation: InvocationOnMock) => WorkbenchEmail(s"PROXY_${invocation.getArgument[WorkbenchUserId](0).value}@example.com"))

    newMigrator(directoryDAO, googleExtensions).removeMatching(pattern, samRequestContext).unsafeRunSync()

    verify(googleDirectoryDAO).removeMemberFromGroup(allUsersGroup.email, WorkbenchEmail("PROXY_good-user@example.com"))
    // both processed, one counted as failed
    verify(directoryDAO).recordAllUsersCleanupProgress(
      ArgumentMatchers.eq(pattern),
      ArgumentMatchers.eq(MigrationState.Completed),
      ArgumentMatchers.eq(Option(2L)),
      ArgumentMatchers.eq(2L),
      ArgumentMatchers.eq(1L),
      any[Option[String]],
      any[SamRequestContext]
    )
  }

  it should "pace Google calls according to the queriesPerMinute override" in {
    val users = (1 to 4).map(i => testUser(s"user$i"))
    val pattern = "tdr-ingest-sa@datarepo-%"

    val directoryDAO = mock[DirectoryDAO]
    val googleExtensions = mock[GoogleExtensions]
    stubStatus(directoryDAO, googleExtensions)
    when(directoryDAO.countUsersByEmailPattern(ArgumentMatchers.eq(pattern), any[SamRequestContext])).thenReturn(IO.pure(4L))
    when(directoryDAO.loadUsersByEmailPattern(ArgumentMatchers.eq(pattern), any[Option[WorkbenchUserId]], any[Int], any[SamRequestContext]))
      .thenReturn(IO.pure(users), IO.pure(Seq.empty))
    when(directoryDAO.removeGroupMember(any[WorkbenchGroupIdentity], any[WorkbenchSubject], any[SamRequestContext]))
      .thenReturn(IO.pure(true))

    val googleDirectoryDAO = mock[GoogleDirectoryDAO]
    when(googleDirectoryDAO.removeMemberFromGroup(any[WorkbenchEmail], any[WorkbenchEmail])).thenReturn(Future.successful(()))
    when(googleExtensions.googleDirectoryDAO).thenReturn(googleDirectoryDAO)
    when(googleExtensions.toProxyFromUser(any[WorkbenchUserId]))
      .thenAnswer((invocation: InvocationOnMock) => WorkbenchEmail(s"PROXY_${invocation.getArgument[WorkbenchUserId](0).value}@example.com"))

    // 240 queries/min => 1 query per user => a 250ms interval between the 4 users, so >= 3 intervals of spacing before the run completes
    val start = System.nanoTime()
    new AllUsersGroupCleanupMigrator(directoryDAO, googleExtensions)
      .removeMatching(pattern, samRequestContext, queriesPerMinuteOverride = Some(240))
      .unsafeRunSync()
    val elapsedMillis = (System.nanoTime() - start) / 1000000

    elapsedMillis should be >= 600L
    verify(googleDirectoryDAO, times(4)).removeMemberFromGroup(any[WorkbenchEmail], any[WorkbenchEmail])
  }

  "previewCount" should "return the count of matching users" in {
    val pattern = "tdr-ingest-sa@datarepo-%"
    val directoryDAO = mock[DirectoryDAO]
    when(directoryDAO.countUsersByEmailPattern(ArgumentMatchers.eq(pattern), any[SamRequestContext])).thenReturn(IO.pure(17342L))

    newMigrator(directoryDAO, mock[GoogleExtensions]).previewCount(pattern, samRequestContext).unsafeRunSync() shouldBe 17342L
  }

  "status" should "return the persisted cleanup records" in {
    val records = Seq(cleanupRecord("tdr-ingest-sa@datarepo-%", MigrationState.Completed, None))
    val directoryDAO = mock[DirectoryDAO]
    when(directoryDAO.listAllUsersCleanupRuns(any[SamRequestContext])).thenReturn(IO.pure(records))

    newMigrator(directoryDAO, mock[GoogleExtensions]).status(samRequestContext).unsafeRunSync() shouldBe records
  }
}
