package org.broadinstitute.dsde.workbench.sam.google

import cats.effect.IO
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
  * @param progressInterval
  *   how often (in groups) to log progress and persist a heartbeat
  * @param staleAfter
  *   a tier whose `running` heartbeat is older than this is treated as a dead run and may be re-claimed (e.g. after a pod restart)
  */
class GoogleGroupExternalMembersMigrator(
    directoryDAO: DirectoryDAO,
    googleExtensions: GoogleExtensions,
    throttleDelay: FiniteDuration = 100.milliseconds,
    progressInterval: Int = 500,
    staleAfter: FiniteDuration = 15.minutes
) extends LazyLogging {

  /** Migrate the given tiers in order, one at a time. Each tier is processed independently: progress is persisted so a `running` tier on another instance is
    * skipped (cross-instance guard), a `completed` tier is skipped when more than one tier was requested, and a crashed/failed tier resumes from its last
    * recorded cursor. Returns immediately to the caller via the endpoint's detached fiber.
    */
  def migrate(tiers: Seq[MigrationTier], samRequestContext: SamRequestContext): IO[Unit] =
    tiers.foldLeft(IO.unit)((acc, tier) => acc >> migrateTier(tier, skipCompleted = tiers.size > 1, samRequestContext))

  /** Current persisted progress for every tier that has been started, for the status endpoint. */
  def status(samRequestContext: SamRequestContext): IO[Seq[ExternalMembersMigrationRecord]] =
    directoryDAO.listExternalMembersMigrations(samRequestContext)

  private def migrateTier(tier: MigrationTier, skipCompleted: Boolean, samRequestContext: SamRequestContext): IO[Unit] =
    directoryDAO.getExternalMembersMigration(tier.value, samRequestContext).flatMap { existing =>
      if (skipCompleted && existing.exists(_.state == MigrationState.Completed))
        IO(logger.info(s"allowExternalMembers migration tier ${tier.value} already completed; skipping"))
      else {
        // resume from the last recorded cursor when a prior run crashed (stale running) or failed; otherwise start from the beginning. note a `completed`
        // single tier (not skipped above) intentionally starts fresh from the beginning rather than its final cursor, i.e. a deliberate full re-run.
        val resumeCursor = existing.collect { case r if r.state == MigrationState.Running || r.state == MigrationState.Failed => r.lastCursor }.flatten
        runTier(tier, resumeCursor, samRequestContext)
      }
    }

  private def runTier(tier: MigrationTier, resumeCursor: Option[String], samRequestContext: SamRequestContext): IO[Unit] =
    directoryDAO.tryClaimExternalMembersMigration(tier.value, resumeCursor, staleAfter, samRequestContext).flatMap {
      case false =>
        IO(logger.info(s"allowExternalMembers migration tier ${tier.value} is already running on another instance; skipping"))
      case true =>
        (for {
          items <- itemsForTier(tier, resumeCursor, samRequestContext)
          total = items.size
          _ <- IO(
            logger.info(
              s"Starting allowExternalMembers migration for tier ${tier.value} ($total groups to process)${resumeCursor.fold("")(c => s", resuming after $c")}"
            )
          )
          _ <- directoryDAO.recordExternalMembersMigration(tier.value, MigrationState.Running, Some(total.toLong), 0, 0, resumeCursor, samRequestContext)
          summary <- migrateItems(tier, items, total, samRequestContext)
          lastCursor = items.lastOption.map(_.cursor).orElse(resumeCursor)
          _ <- directoryDAO.recordExternalMembersMigration(
            tier.value,
            MigrationState.Completed,
            Some(total.toLong),
            summary.processed.toLong,
            summary.failed.toLong,
            lastCursor,
            samRequestContext
          )
          _ <- IO(logger.info(s"Finished allowExternalMembers migration for tier ${tier.value}: $summary of $total"))
        } yield ()).handleErrorWith { t =>
          // an enumeration/persistence failure aborts this tier; mark it failed (preserving the last heartbeat) so it can be resumed, and keep going
          IO(logger.error(s"allowExternalMembers migration for tier ${tier.value} failed", t)) >>
            directoryDAO.setExternalMembersMigrationState(tier.value, MigrationState.Failed, samRequestContext).handleError(_ => ())
        }
    }

  // Load every group in the tier as a uniform MigrationItem. Resource-type groups only contain in-domain proxy-group emails as members, so flipping the setting
  // is enough. A proxy group holds the user's real (possibly external) email, which could not be added while external members were disallowed, so it also
  // re-adds that email once the setting is on. Pet service accounts are internally managed and aren't expected to be missing, so they are left untouched.
  private def itemsForTier(tier: MigrationTier, resumeCursor: Option[String], samRequestContext: SamRequestContext): IO[Seq[MigrationItem]] =
    tier match {
      case MigrationTier.Proxy =>
        directoryDAO
          .loadEnabledUsers(resumeCursor.map(WorkbenchUserId), samRequestContext)
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
          .loadSynchronizedGroupEmailsByResourceType(resourceTypeName, resumeCursor.map(WorkbenchEmail), samRequestContext)
          .map(_.map(groupEmail => MigrationItem(groupEmail, groupEmail.value, IO.unit)))
    }

  // Process the groups one at a time, throttled, accumulating a summary. Failures are logged and counted, never aborting the run.
  private def migrateItems(
      tier: MigrationTier,
      items: Seq[MigrationItem],
      total: Int,
      samRequestContext: SamRequestContext
  ): IO[GroupExternalMembersMigrationSummary] =
    items.zipWithIndex.foldLeft(IO.pure(GroupExternalMembersMigrationSummary.empty)) { case (acc, (item, index)) =>
      acc.flatMap { summary =>
        for {
          _ <- IO.sleep(throttleDelay)
          succeeded <- processItem(item)
          updated = summary.record(succeeded)
          _ <- checkpoint(tier, processed = index + 1, total, updated, item.cursor, samRequestContext)
        } yield updated
      }
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

  // Every `progressInterval` groups, log progress and persist a heartbeat (counts + resume cursor) so progress survives a restart and the status endpoint
  // stays current.
  private def checkpoint(
      tier: MigrationTier,
      processed: Int,
      total: Int,
      summary: GroupExternalMembersMigrationSummary,
      cursor: String,
      samRequestContext: SamRequestContext
  ): IO[Unit] =
    IO.whenA(processed % progressInterval == 0)(
      IO(logger.info(s"allowExternalMembers migration for tier ${tier.value}: processed $processed of $total (resume after: $cursor)")) >>
        directoryDAO.recordExternalMembersMigration(
          tier.value,
          MigrationState.Running,
          Some(total.toLong),
          summary.processed.toLong,
          summary.failed.toLong,
          Some(cursor),
          samRequestContext
        )
    )
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

sealed trait MigrationTier extends ValueObject
object MigrationTier {
  case object Proxy extends MigrationTier {
    val value = "proxy"
  }
  case class ResourceType(resourceTypeName: ResourceTypeName) extends MigrationTier {
    val value: String = resourceTypeName.value
  }

  def fromSelector(selector: String): MigrationTier =
    if (selector == Proxy.value) Proxy else ResourceType(ResourceTypeName(selector))
}

/** @param processed
  *   total groups processed
  * @param failed
  *   groups where the migration raised an error (and was skipped)
  */
case class GroupExternalMembersMigrationSummary(processed: Int, failed: Int) {
  def record(succeeded: Boolean): GroupExternalMembersMigrationSummary =
    if (succeeded) copy(processed = processed + 1) else copy(processed = processed + 1, failed = failed + 1)
}
object GroupExternalMembersMigrationSummary {
  val empty: GroupExternalMembersMigrationSummary = GroupExternalMembersMigrationSummary(0, 0)
}
