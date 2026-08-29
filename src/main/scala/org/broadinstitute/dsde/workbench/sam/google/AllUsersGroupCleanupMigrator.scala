package org.broadinstitute.dsde.workbench.sam.google

import cats.effect.{IO, Ref}
import cats.effect.std.Semaphore
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.{WorkbenchGroup, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{AllUsersCleanupRecord, DirectoryDAO, MigrationState}
import org.broadinstitute.dsde.workbench.sam.model.api.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/** One-off cleanup that removes Sam users whose email matches a given pattern from the All_Users group, in both Sam's own Postgres membership record and the
  * real mirrored Google Group. Used to detach large batches of non-interactive accounts (e.g. ingest service accounts) from All_Users without touching their
  * direct resource-policy grants, which are resolved independently of All_Users membership.
  *
  * A run is keyed by its email pattern (analogous to a [[GoogleGroupExternalMembersMigrator]] tier), so the operator controls pacing by invoking once per
  * pattern, and a crashed/failed run resumes from its last recorded cursor. Every operation is idempotent: removing a member who is already gone is a no-op on
  * both the Postgres and Google sides, so a run can be re-invoked safely.
  *
  * All Google calls go through the coordinated-backoff [[GoogleDirectoryDAO]] so a quota trip backs off Sam (and therefore Terra) traffic gracefully. On top of
  * that, work is paced to a configurable queries-per-minute rate to stay under Google's Directory API quota, the same pacing scheme as
  * [[GoogleGroupExternalMembersMigrator]].
  *
  * @param directoryDAO
  *   the background directory DAO, used to enumerate matching users without crowding foreground api calls, and to persist per-pattern run progress
  * @param googleExtensions
  *   provides the coordinated-backoff google directory DAO, the All_Users group, and proxy email derivation
  * @param defaultQueriesPerMinute
  *   default rate limit in Directory API queries per minute. Each user costs one query (a member delete), so user throughput is roughly this rate. Conservative
  *   by default so a bare invocation is safe against the current quota; the operator overrides it per run once a higher quota is granted.
  * @param maxConcurrency
  *   safety ceiling on users in flight at once, not a throughput knob (the rate limiter is that). Only bites if Google stalls; sized so it never limits the
  *   paced rate under normal latency.
  * @param pageSize
  *   how many users to load per keyset-paginated query, so a run never materializes its whole match set in memory at once; also the heartbeat cadence (one
  *   progress record per page)
  */
class AllUsersGroupCleanupMigrator(
    directoryDAO: DirectoryDAO,
    googleExtensions: GoogleExtensions,
    defaultQueriesPerMinute: Int = 300,
    maxConcurrency: Int = 128,
    pageSize: Int = 1000
) extends LazyLogging {

  /** Remove every user whose email matches `emailPattern` (a SQL `LIKE` pattern; a plain email with no `%`/`_` wildcards behaves as an exact-match lookup) from
    * the All_Users group. Returns immediately to the caller via the endpoint's detached fiber.
    *
    * The rate override is per-invocation and not persisted, so a resume may run at a different rate than the original; the run just picks up from its cursor at
    * whatever rate this call specifies. There is no locking: re-firing while a run is live starts a *second* runner for the same pattern with its own rate
    * limiter (so the two rates add up and overshoot the limit), on top of doubling the idempotent work. The operator must confirm a run has ended (via the
    * status endpoint) before re-running. A `completed` pattern re-run starts fresh, i.e. a deliberate full re-run; anything `running`/`failed` resumes from its
    * last recorded cursor.
    */
  def removeMatching(emailPattern: String, samRequestContext: SamRequestContext, queriesPerMinuteOverride: Option[Int] = None)(implicit
      executionContext: ExecutionContext
  ): IO[Unit] = {
    val queriesPerMinute = queriesPerMinuteOverride.getOrElse(defaultQueriesPerMinute)
    directoryDAO.getAllUsersCleanupRun(emailPattern, samRequestContext).flatMap { existing =>
      val resumable = existing.filter(r => r.state == MigrationState.Running || r.state == MigrationState.Failed)
      runCleanup(
        emailPattern,
        resumeCursor = resumable.flatMap(_.lastCursor),
        base = AllUsersCleanupSummary(
          resumable.map(_.processed).getOrElse(0L),
          resumable.map(_.failed).getOrElse(0L),
          resumable.flatMap(_.lastCursor)
        ),
        knownTotal = resumable.flatMap(_.total),
        queriesPerMinute,
        samRequestContext
      )
    }
  }

  /** The count of users currently matching `emailPattern`, for a cheap synchronous preview before committing to a removal run. */
  def previewCount(emailPattern: String, samRequestContext: SamRequestContext): IO[Long] =
    directoryDAO.countUsersByEmailPattern(emailPattern, samRequestContext)

  /** Current persisted progress for every pattern that has been run, for the status endpoint. */
  def status(samRequestContext: SamRequestContext): IO[Seq[AllUsersCleanupRecord]] =
    directoryDAO.listAllUsersCleanupRuns(samRequestContext)

  private def runCleanup(
      emailPattern: String,
      resumeCursor: Option[String],
      base: AllUsersCleanupSummary,
      knownTotal: Option[Long],
      queriesPerMinute: Int,
      samRequestContext: SamRequestContext
  )(implicit executionContext: ExecutionContext): IO[Unit] =
    (for {
      allUsersGroup <- googleExtensions.getOrCreateAllUsersGroup(directoryDAO, samRequestContext)
      total <- knownTotal.map(IO.pure).getOrElse(directoryDAO.countUsersByEmailPattern(emailPattern, samRequestContext))
      _ <- IO(
        logger.info(
          s"Starting All_Users cleanup for pattern $emailPattern ($total users to process)${resumeCursor.fold("")(c => s", resuming after $c")}"
        )
      )
      _ <- recordProgress(emailPattern, MigrationState.Running, total, base, samRequestContext)
      summary <- removePages(emailPattern, allUsersGroup, resumeCursor, total, base, queriesPerMinute, samRequestContext)
      _ <- recordProgress(emailPattern, MigrationState.Completed, total, summary, samRequestContext)
      _ <- IO(logger.info(s"Finished All_Users cleanup for pattern $emailPattern: $summary of $total"))
    } yield ()).handleErrorWith { t =>
      // an enumeration/persistence failure aborts this run; mark it failed (preserving the last heartbeat) so it can be resumed, and keep going
      IO(logger.error(s"All_Users cleanup for pattern $emailPattern failed", t)) >>
        directoryDAO.setAllUsersCleanupState(emailPattern, MigrationState.Failed, samRequestContext).handleError(_ => ())
    }

  // Keyset-paginate through the matches one page at a time so the whole population is never held in memory, accumulating a cumulative summary (seeded from
  // any resumed progress). iterateUntilM loads and processes each page until one comes back short, i.e. the last page. Within a page, users are removed in
  // parallel, but each first waits on a shared rate limiter that spaces out removal *starts* (a per-minute average, not an instantaneous cap), then takes a
  // `maxConcurrency` permit. Pacing alone keeps concurrency near rate*latency; the semaphore is just a safety cap for when Google stalls. Per-item failures
  // are logged and counted, never aborting the run.
  private def removePages(
      emailPattern: String,
      allUsersGroup: WorkbenchGroup,
      startCursor: Option[String],
      total: Long,
      base: AllUsersCleanupSummary,
      queriesPerMinute: Int,
      samRequestContext: SamRequestContext
  ): IO[AllUsersCleanupSummary] =
    (Semaphore[IO](maxConcurrency.toLong), Ref.of[IO, FiniteDuration](Duration.Zero)).tupled.flatMap { case (permits, nextSlot) =>
      val interval = userInterval(queriesPerMinute)

      def processNextPage(state: CleanupPageProgress): IO[CleanupPageProgress] =
        directoryDAO.loadUsersByEmailPattern(emailPattern, state.cursor.map(WorkbenchUserId), pageSize, samRequestContext).flatMap { page =>
          page.toList
            .parTraverse(user => paced(interval, nextSlot)(permits.permit.use(_ => processUser(user, allUsersGroup, samRequestContext))))
            .flatMap { outcomes =>
              val summary = state.summary.copy(
                processed = state.summary.processed + page.size,
                failed = state.summary.failed + outcomes.count(succeeded => !succeeded),
                lastCursor = page.lastOption.map(_.id.value).orElse(state.summary.lastCursor)
              )
              val next = CleanupPageProgress(summary.lastCursor, summary, done = page.size < pageSize)
              recordPageProgress(emailPattern, total, summary, samRequestContext).as(next)
            }
        }

      CleanupPageProgress(startCursor, base, done = false)
        .iterateUntilM(processNextPage)(_.done)
        .map(_.summary)
    }

  // Wall-clock spacing between two removal starts that keeps the call rate under `queriesPerMinute`. Each user costs one Directory API query (the Google
  // member delete; the Postgres removal doesn't count against Google's quota). A non-positive rate disables pacing.
  private def userInterval(queriesPerMinute: Int): FiniteDuration =
    if (queriesPerMinute <= 0) Duration.Zero else (60000L / queriesPerMinute).milliseconds

  // Reserve the next start slot `interval` after the previous one (never in the past), then sleep until it before running `task`. The Ref holds the next
  // free slot as a monotonic timestamp; `modify` claims one atomically so concurrent callers serialize their starts instead of bunching up.
  private def paced[A](interval: FiniteDuration, nextSlot: Ref[IO, FiniteDuration])(task: IO[A]): IO[A] =
    for {
      now <- IO.monotonic
      waitFor <- nextSlot.modify { next =>
        val start = if (next > now) next else now
        (start + interval, start - now)
      }
      _ <- IO.sleep(if (waitFor > Duration.Zero) waitFor else Duration.Zero)
      result <- task
    } yield result

  // Remove one user from All_Users: the Postgres membership row, then the real Google Group membership, keyed by the user's proxy email (All_Users only
  // ever holds proxy emails, added at user creation via GoogleExtensions.onUserCreate) rather than their real email. A member already gone on either side is
  // a no-op, so this is safe to re-run.
  private def processUser(user: SamUser, allUsersGroup: WorkbenchGroup, samRequestContext: SamRequestContext): IO[Boolean] =
    (for {
      _ <- directoryDAO.removeGroupMember(allUsersGroup.id, user.id, samRequestContext)
      proxyEmail = googleExtensions.toProxyFromUser(user.id)
      _ <- IO.fromFuture(IO(googleExtensions.googleDirectoryDAO.removeMemberFromGroup(allUsersGroup.email, proxyEmail)))
    } yield true).handleError { t =>
      logger.warn(s"Failed to remove ${user.id} from All_Users", t)
      false
    }

  // After each page, log progress and persist a heartbeat (cumulative counts + resume cursor) so progress survives a restart and the status endpoint stays
  // current.
  private def recordPageProgress(
      emailPattern: String,
      total: Long,
      summary: AllUsersCleanupSummary,
      samRequestContext: SamRequestContext
  ): IO[Unit] =
    IO(
      logger.info(
        s"All_Users cleanup for pattern $emailPattern: processed ${summary.processed} of $total (resume after: ${summary.lastCursor.getOrElse("-")})"
      )
    ) >> recordProgress(emailPattern, MigrationState.Running, total, summary, samRequestContext)

  private def recordProgress(
      emailPattern: String,
      state: String,
      total: Long,
      summary: AllUsersCleanupSummary,
      samRequestContext: SamRequestContext
  ): IO[Unit] =
    directoryDAO.recordAllUsersCleanupProgress(emailPattern, state, Some(total), summary.processed, summary.failed, summary.lastCursor, samRequestContext)
}

/** The loop state threaded through the per-page cleanup run: where to resume, the cumulative summary so far, and whether the last page was the final (short)
  * one.
  */
private final case class CleanupPageProgress(cursor: Option[String], summary: AllUsersCleanupSummary, done: Boolean)

/** Cumulative progress of a cleanup run.
  *
  * @param processed
  *   total users processed
  * @param failed
  *   users where the removal raised an error (and was skipped)
  * @param lastCursor
  *   the resume cursor (user id) of the most recently processed user, persisted so a crashed run resumes after it
  */
case class AllUsersCleanupSummary(processed: Long, failed: Long, lastCursor: Option[String] = None)
