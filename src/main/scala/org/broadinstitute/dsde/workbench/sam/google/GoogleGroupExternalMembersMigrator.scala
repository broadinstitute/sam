package org.broadinstitute.dsde.workbench.sam.google

import cats.effect.IO
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.{ValueObject, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.dataAccess.{DirectoryDAO, ExternalMembersMigrationRecord, MigrationState}
import org.broadinstitute.dsde.workbench.sam.model.ResourceTypeName
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.duration._

/** One-off migration that ensures `allowExternalMembers` is enabled on existing Google groups that Sam created before that setting was applied at creation
  * time.
  *
  * Groups are processed in priority tiers (see [[MigrationTier]]) so the operator controls ordering and pacing by invoking the admin endpoint once per tier.
  * Every operation is idempotent: [[GoogleDirectoryDAO.enableExternalMembersIfNeeded]] only writes when the setting is currently false, and re-adding a user to
  * their proxy group is a no-op when they are already a member, so a tier can be re-run safely (e.g. after a quota backoff).
  *
  * All Google calls go through the coordinated-backoff [[GoogleDirectoryDAO]] so a quota trip backs off Sam (and therefore Terra) traffic gracefully. Work is
  * additionally throttled (a fixed `IO.sleep` between items) to proactively stay well under Google's quota; speed is intentionally sacrificed for safety.
  *
  * @param directoryDAO
  *   the background directory DAO, used to enumerate users/groups without crowding foreground api calls, and to persist per-tier migration progress
  * @param googleExtensions
  *   provides the coordinated-backoff google directory DAO and proxy email derivation
  * @param throttleDelay
  *   minimum delay between processing items, the proactive rate limit
  * @param pageSize
  *   how many items to load per keyset-paginated query, so a tier never materializes its whole population in memory at once; also the heartbeat cadence (one
  *   progress record per page)
  */
class GoogleGroupExternalMembersMigrator(
    directoryDAO: DirectoryDAO,
    googleExtensions: GoogleExtensions,
    throttleDelay: FiniteDuration = 100.milliseconds,
    pageSize: Int = 1000
) extends LazyLogging {

  /** Migrate the given tiers in order, one at a time. Each tier is processed independently: a `completed` tier is skipped when more than one tier was
    * requested, and a crashed/failed tier resumes from its last recorded cursor. Returns immediately to the caller via the endpoint's detached fiber. This is a
    * one-off operator-driven migration with no cross-instance locking: re-firing while a run is in flight would double up that tier's idempotent work (capped
    * by the coordinated quota backoff), so the operator should check the status endpoint before re-running.
    */
  def migrate(tiers: List[MigrationTier], samRequestContext: SamRequestContext): IO[Unit] =
    tiers.traverse_(tier => migrateTier(tier, skipCompleted = tiers.size > 1, samRequestContext))

  /** Current persisted progress for every tier that has been started, for the status endpoint. */
  def status(samRequestContext: SamRequestContext): IO[Seq[ExternalMembersMigrationRecord]] =
    directoryDAO.listExternalMembersMigrations(samRequestContext)

  private def migrateTier(tier: MigrationTier, skipCompleted: Boolean, samRequestContext: SamRequestContext): IO[Unit] =
    directoryDAO.getExternalMembersMigration(tier.value, samRequestContext).flatMap { existing =>
      if (skipCompleted && existing.exists(_.state == MigrationState.Completed))
        IO(logger.info(s"allowExternalMembers migration tier ${tier.value} already completed; skipping"))
      else {
        // resume from a prior run that crashed (running) or failed, carrying forward its cursor and cumulative counts/total so the status report stays
        // cumulative across resumes. a `completed` single tier (not skipped above) intentionally starts fresh from the beginning, i.e. a deliberate full re-run.
        val resumable = existing.filter(r => r.state == MigrationState.Running || r.state == MigrationState.Failed)
        runTier(
          tier,
          resumeCursor = resumable.flatMap(_.lastCursor),
          base = GroupExternalMembersMigrationSummary(
            resumable.map(_.processed).getOrElse(0L),
            resumable.map(_.failed).getOrElse(0L),
            resumable.flatMap(_.lastCursor)
          ),
          knownTotal = resumable.flatMap(_.total),
          samRequestContext
        )
      }
    }

  private def runTier(
      tier: MigrationTier,
      resumeCursor: Option[String],
      base: GroupExternalMembersMigrationSummary,
      knownTotal: Option[Long],
      samRequestContext: SamRequestContext
  ): IO[Unit] =
    (for {
      total <- knownTotal.map(IO.pure).getOrElse(countForTier(tier, samRequestContext))
      _ <- IO(
        logger.info(
          s"Starting allowExternalMembers migration for tier ${tier.value} ($total groups to process)${resumeCursor.fold("")(c => s", resuming after $c")}"
        )
      )
      _ <- recordProgress(tier, MigrationState.Running, total, base, samRequestContext)
      summary <- migratePages(tier, resumeCursor, total, base, samRequestContext)
      _ <- recordProgress(tier, MigrationState.Completed, total, summary, samRequestContext)
      _ <- IO(logger.info(s"Finished allowExternalMembers migration for tier ${tier.value}: $summary of $total"))
    } yield ()).handleErrorWith { t =>
      // an enumeration/persistence failure aborts this tier; mark it failed (preserving the last heartbeat) so it can be resumed, and keep going
      IO(logger.error(s"allowExternalMembers migration for tier ${tier.value} failed", t)) >>
        directoryDAO.setExternalMembersMigrationState(tier.value, MigrationState.Failed, samRequestContext).handleError(_ => ())
    }

  // The whole-population size for a tier, recorded once so the status report shows cumulative progress against a stable denominator.
  private def countForTier(tier: MigrationTier, samRequestContext: SamRequestContext): IO[Long] =
    tier match {
      case MigrationTier.Proxy => directoryDAO.countEnabledUsers(samRequestContext)
      case MigrationTier.ResourceType(resourceTypeName) => directoryDAO.countSynchronizedGroupEmailsByResourceType(resourceTypeName, samRequestContext)
    }

  // Keyset-paginate through the tier a page at a time so the whole population is never held in memory, accumulating a cumulative summary (seeded from any
  // resumed progress). Each step loads and processes the next page; iterateUntilM repeats it until a page comes back short, i.e. the last page. Per-item
  // failures are logged and counted, never aborting the run.
  private def migratePages(
      tier: MigrationTier,
      startCursor: Option[String],
      total: Long,
      base: GroupExternalMembersMigrationSummary,
      samRequestContext: SamRequestContext
  ): IO[GroupExternalMembersMigrationSummary] = {
    def processNextPage(state: PageProgress): IO[PageProgress] =
      itemsForTier(tier, state.cursor, samRequestContext).flatMap { page =>
        page
          .traverse(item => IO.sleep(throttleDelay) >> processItem(item))
          .flatMap { outcomes =>
            val summary = state.summary.copy(
              processed = state.summary.processed + page.size,
              failed = state.summary.failed + outcomes.count(succeeded => !succeeded),
              lastCursor = page.lastOption.map(_.cursor).orElse(state.summary.lastCursor)
            )
            val next = PageProgress(summary.lastCursor, summary, done = page.size < pageSize)
            recordPageProgress(tier, total, summary, samRequestContext).as(next)
          }
      }

    PageProgress(startCursor, base, done = false)
      .iterateUntilM(processNextPage)(_.done)
      .map(_.summary)
  }

  // Load one page of the tier as uniform MigrationItems. Resource-type groups only contain in-domain proxy-group emails as members, so flipping the setting is
  // enough. A proxy group holds the user's real (possibly external) email, which could not be added while external members were disallowed, so it also re-adds
  // that email once the setting is on. Pet service accounts are internally managed and aren't expected to be missing, so they are left untouched.
  private def itemsForTier(tier: MigrationTier, cursor: Option[String], samRequestContext: SamRequestContext): IO[Seq[MigrationItem]] =
    tier match {
      case MigrationTier.Proxy =>
        directoryDAO
          .loadEnabledUsers(cursor.map(WorkbenchUserId), pageSize, samRequestContext)
          .map(_.map { user =>
            val proxyEmail = googleExtensions.toProxyFromUser(user.id)
            MigrationItem(
              proxyEmail,
              user.id.value,
              IO.fromFuture(IO(googleExtensions.googleDirectoryDAO.addMemberToGroup(proxyEmail, WorkbenchEmail(user.email.value))))
            )
          })
      case MigrationTier.ResourceType(resourceTypeName) =>
        directoryDAO
          .loadSynchronizedGroupEmailsByResourceType(resourceTypeName, cursor.map(WorkbenchEmail), pageSize, samRequestContext)
          .map(_.map(groupEmail => MigrationItem(groupEmail, groupEmail.value, IO.unit)))
    }

  // Enable external members on a single group, then run its follow-up action (the proxy-group re-add, or nothing for resource-type groups).
  private def processItem(item: MigrationItem): IO[Boolean] =
    (for {
      _ <- IO.fromFuture(IO(googleExtensions.googleDirectoryDAO.enableExternalMembersIfNeeded(item.groupEmail)))
      _ <- item.followUp
    } yield true).handleError { t =>
      logger.warn(s"Failed to migrate allowExternalMembers for group ${item.groupEmail}", t)
      false
    }

  // After each page, log progress and persist a heartbeat (cumulative counts + resume cursor) so progress survives a restart and the status endpoint stays
  // current.
  private def recordPageProgress(
      tier: MigrationTier,
      total: Long,
      summary: GroupExternalMembersMigrationSummary,
      samRequestContext: SamRequestContext
  ): IO[Unit] =
    IO(
      logger.info(
        s"allowExternalMembers migration for tier ${tier.value}: processed ${summary.processed} of $total (resume after: ${summary.lastCursor.getOrElse("-")})"
      )
    ) >> recordProgress(tier, MigrationState.Running, total, summary, samRequestContext)

  private def recordProgress(
      tier: MigrationTier,
      state: String,
      total: Long,
      summary: GroupExternalMembersMigrationSummary,
      samRequestContext: SamRequestContext
  ): IO[Unit] =
    directoryDAO.recordExternalMembersMigration(tier.value, state, Some(total), summary.processed, summary.failed, summary.lastCursor, samRequestContext)
}

/** A single group to migrate, paired with the cursor that resumes after it and a follow-up action to run once the setting is flipped.
  *
  * @param groupEmail
  *   the Google group to enable external members on
  * @param cursor
  *   the resume cursor for this group (its user id or email)
  * @param followUp
  *   extra work after the flip (proxy-group member re-add, or `IO.unit` when none is needed)
  */
private final case class MigrationItem(groupEmail: WorkbenchEmail, cursor: String, followUp: IO[Unit])

/** The loop state threaded through the per-page migration: where to resume, the cumulative summary so far, and whether the last page was the final (short) one.
  */
private final case class PageProgress(cursor: Option[String], summary: GroupExternalMembersMigrationSummary, done: Boolean)

sealed trait MigrationTier extends ValueObject
object MigrationTier {
  case object Proxy extends MigrationTier {
    val value = "proxy"
  }
  case class ResourceType(resourceTypeName: ResourceTypeName) extends MigrationTier {
    val value: String = resourceTypeName.value
  }

  /** Parse a tier selector, returning `None` for an unknown one. The proxy tier is the literal "proxy"; any other selector must be a known resource type name
    * so a typo'd tier is rejected rather than silently "completing" zero groups.
    */
  def fromSelector(selector: String, validResourceTypes: Set[String]): Option[MigrationTier] =
    if (selector == Proxy.value) Some(Proxy)
    else if (validResourceTypes.contains(selector)) Some(ResourceType(ResourceTypeName(selector)))
    else None
}

/** Cumulative progress of a tier run.
  *
  * @param processed
  *   total groups processed
  * @param failed
  *   groups where the migration raised an error (and was skipped)
  * @param lastCursor
  *   the resume cursor of the most recently processed group, persisted so a crashed run resumes after it
  */
case class GroupExternalMembersMigrationSummary(processed: Long, failed: Long, lastCursor: Option[String] = None)
