package org.broadinstitute.dsde.workbench.sam.google

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.model.{ValueObject, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
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
  *   the background directory DAO, used to enumerate users/groups without crowding foreground api calls
  * @param googleExtensions
  *   provides the coordinated-backoff google directory DAO and proxy email derivation
  * @param throttleDelay
  *   minimum delay between processing items, the proactive rate limit
  */
class GoogleGroupExternalMembersMigrator(
    directoryDAO: DirectoryDAO,
    googleExtensions: GoogleExtensions,
    throttleDelay: FiniteDuration = 100.milliseconds,
    progressInterval: Int = 500
) extends LazyLogging {

  /** Flip `allowExternalMembers` on every synchronized Google group for a tier, plus (for the proxy tier) re-add the user's email that could not be added while
    * the setting was false. Returns a summary of what happened.
    *
    * @param after
    *   resume cursor: when set, only groups strictly after this value are loaded (a user id for the proxy tier, a group email for resource-type tiers). Use the
    *   cursor from the last progress log line to resume after a restart; re-processing already-done groups is harmless since every operation is idempotent.
    */
  def migrate(tier: MigrationTier, after: Option[String], samRequestContext: SamRequestContext): IO[GroupExternalMembersMigrationSummary] =
    (for {
      items <- itemsForTier(tier, after, samRequestContext)
      total = items.size
      _ <- IO(
        logger.info(s"Starting allowExternalMembers migration for tier ${tier.value} ($total groups to process)${after.fold("")(c => s", resuming after $c")}")
      )
      summary <- migrateItems(tier, items, total)
      _ <- IO(logger.info(s"Finished allowExternalMembers migration for tier ${tier.value}: $summary of $total"))
    } yield summary)
      // the endpoint runs this in a detached fiber, so make sure an enumeration failure is logged rather than lost
      .onError(t => IO(logger.error(s"allowExternalMembers migration for tier ${tier.value} failed", t)))

  // Load every group in the tier as a uniform MigrationItem. Resource-type groups only contain in-domain proxy-group emails as members, so flipping the setting
  // is enough. A proxy group holds the user's real (possibly external) email, which could not be added while external members were disallowed, so it also
  // re-adds that email once the setting is on. Pet service accounts are internally managed and aren't expected to be missing, so they are left untouched.
  private def itemsForTier(tier: MigrationTier, after: Option[String], samRequestContext: SamRequestContext): IO[Seq[MigrationItem]] =
    tier match {
      case MigrationTier.Proxy =>
        directoryDAO
          .loadEnabledUsers(after.map(WorkbenchUserId), samRequestContext)
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
          .loadSynchronizedGroupEmailsByResourceType(resourceTypeName, after.map(WorkbenchEmail), samRequestContext)
          .map(_.map(groupEmail => MigrationItem(groupEmail, groupEmail.value, IO.unit)))
    }

  // Process the groups one at a time, throttled, accumulating a summary. Failures are logged and counted, never aborting the run.
  private def migrateItems(tier: MigrationTier, items: Seq[MigrationItem], total: Int): IO[GroupExternalMembersMigrationSummary] =
    items.zipWithIndex.foldLeft(IO.pure(GroupExternalMembersMigrationSummary.empty)) { case (acc, (item, index)) =>
      acc.flatMap { summary =>
        for {
          _ <- IO.sleep(throttleDelay)
          succeeded <- processItem(item)
          _ <- logProgress(tier, processed = index + 1, total, item.cursor)
        } yield summary.record(succeeded)
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

  // Log a progress line every `progressInterval` groups, including the resume cursor so a restart can pick up from there.
  private def logProgress(tier: MigrationTier, processed: Int, total: Int, cursor: String): IO[Unit] =
    IO.whenA(processed % progressInterval == 0)(
      IO(logger.info(s"allowExternalMembers migration for tier ${tier.value}: processed $processed of $total (resume after: $cursor)"))
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
