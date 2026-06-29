package org.broadinstitute.dsde.workbench.sam.google

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import fs2.Stream
import org.broadinstitute.dsde.workbench.model.{ValueObject, WorkbenchEmail, WorkbenchUserId}
import org.broadinstitute.dsde.workbench.sam.dataAccess.DirectoryDAO
import org.broadinstitute.dsde.workbench.sam.model.ResourceTypeName
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

import scala.concurrent.duration._

/** One-off migration that ensures `allowExternalMembers` is enabled on existing Google groups that Sam created before that setting was applied at creation
  * time.
  *
  * Groups are processed in priority tiers (see [[MigrationTier]]) so the operator controls ordering and pacing by invoking the admin endpoint once per tier.
  * Every operation is idempotent: [[GoogleDirectoryDAO.enableExternalMembersIfNeeded]] only writes when the setting is currently false, and
  * [[GoogleExtensions.onUserEnable]] is safe to repeat, so a tier can be re-run safely (e.g. after a quota backoff).
  *
  * All Google calls go through the coordinated-backoff [[GoogleDirectoryDAO]] so a quota trip backs off Sam (and therefore Terra) traffic gracefully. Work is
  * additionally metered to proactively stay well under Google's quota; speed is intentionally sacrificed for safety.
  *
  * @param directoryDAO
  *   the background directory DAO, used to enumerate users/groups without crowding foreground api calls
  * @param googleExtensions
  *   provides the coordinated-backoff google directory DAO, proxy email derivation, and the `onUserEnable` proxy re-add primitive
  * @param throttleDelay
  *   minimum delay between processing items, the proactive rate limit
  * @param pageSize
  *   number of rows fetched per enumeration query
  */
class GoogleGroupExternalMembersMigrator(
    directoryDAO: DirectoryDAO,
    googleExtensions: GoogleExtensions,
    throttleDelay: FiniteDuration = 100.milliseconds,
    pageSize: Int = 200,
    progressInterval: Long = 500
) extends LazyLogging {

  /** Flip `allowExternalMembers` on every synchronized Google group for a tier, plus (for the proxy tier) re-add members that may have been dropped while the
    * setting was false. Returns a summary of what happened.
    *
    * @param after
    *   resume cursor: when set, processing starts strictly after this value (a user id for the proxy tier, a group email for resource-type tiers). Use the
    *   cursor from the last progress log line to resume after a restart. Because the cursor is only logged every `progressInterval` groups, resuming may
    *   re-process up to that many already-done groups, which is harmless since every operation is idempotent.
    */
  def migrate(tier: MigrationTier, after: Option[String], samRequestContext: SamRequestContext): IO[GroupExternalMembersMigrationSummary] =
    (for {
      total <- countForTier(tier, samRequestContext)
      _ <- IO(
        logger.info(s"Starting allowExternalMembers migration for tier ${tier.value} ($total groups to process)${after.fold("")(c => s", resuming after $c")}")
      )
      summary <- streamForTier(tier, after, samRequestContext).zipWithIndex
        .evalTap { case ((_, cursor), index) => logProgress(tier, processed = index + 1, total, cursor) }
        .map { case ((succeeded, _), _) => succeeded }
        .compile
        .fold(GroupExternalMembersMigrationSummary.empty)(_.record(_))
      _ <- IO(logger.info(s"Finished allowExternalMembers migration for tier ${tier.value}: $summary of $total"))
    } yield summary)
      // the endpoint runs this in a detached fiber, so make sure an enumeration failure is logged rather than lost
      .onError(t => IO(logger.error(s"allowExternalMembers migration for tier ${tier.value} failed", t)))

  private def countForTier(tier: MigrationTier, samRequestContext: SamRequestContext): IO[Long] =
    tier match {
      case MigrationTier.Proxy => directoryDAO.countEnabledUsers(samRequestContext)
      case MigrationTier.ResourceType(resourceTypeName) => directoryDAO.countSynchronizedGroupsByResourceType(resourceTypeName, samRequestContext)
    }

  // Each emitted element is (didItSucceed, cursorValue), where cursorValue is the resume cursor for that group (its user id or email).
  private def streamForTier(tier: MigrationTier, after: Option[String], samRequestContext: SamRequestContext): Stream[IO, (Boolean, String)] =
    tier match {
      case MigrationTier.Proxy => migrateProxyGroups(after.map(WorkbenchUserId), samRequestContext)
      case MigrationTier.ResourceType(resourceTypeName) => migrateResourceTypeGroups(resourceTypeName, after.map(WorkbenchEmail), samRequestContext)
    }

  // Log a progress line every `progressInterval` groups, including the resume cursor so a restart can pick up from there.
  private def logProgress(tier: MigrationTier, processed: Long, total: Long, cursor: String): IO[Unit] =
    IO.whenA(processed % progressInterval == 0)(
      IO(logger.info(s"allowExternalMembers migration for tier ${tier.value}: processed $processed of $total (resume after: $cursor)"))
    )

  // Proxy groups are the only groups that hold a user's real (possibly external) email, so they are also the only place memberships could have been dropped.
  // For each enabled user: enable external members on their proxy group, then re-add the user (and their pet service accounts) via onUserEnable.
  private def migrateProxyGroups(after: Option[WorkbenchUserId], samRequestContext: SamRequestContext): Stream[IO, (Boolean, String)] =
    pagedStream(after)(afterId => directoryDAO.loadEnabledUsers(afterId, pageSize, samRequestContext))(_.id)
      .metered(throttleDelay)
      .evalMap(user => processItem(googleExtensions.toProxyFromUser(user.id))(googleExtensions.onUserEnable(user, samRequestContext)).map((_, user.id.value)))

  // Resource/policy groups only contain in-domain proxy-group emails as members, so they were never impacted; flip the setting only, no re-add needed.
  private def migrateResourceTypeGroups(
      resourceTypeName: ResourceTypeName,
      after: Option[WorkbenchEmail],
      samRequestContext: SamRequestContext
  ): Stream[IO, (Boolean, String)] =
    pagedStream(after)(afterEmail => directoryDAO.loadSynchronizedGroupEmailsByResourceType(resourceTypeName, afterEmail, pageSize, samRequestContext))(
      identity
    )
      .metered(throttleDelay)
      .evalMap(groupEmail => processItem(groupEmail)(IO.unit).map((_, groupEmail.value)))

  // Enable external members on a single group, then run an optional follow-up action (re-add). Failures are logged and counted, never aborting the run.
  private def processItem(groupEmail: WorkbenchEmail)(followUp: => IO[Unit]): IO[Boolean] =
    (for {
      _ <- IO.fromFuture(IO(googleExtensions.googleDirectoryDAO.enableExternalMembersIfNeeded(groupEmail)))
      _ <- followUp
    } yield true).handleError { t =>
      logger.warn(s"Failed to migrate allowExternalMembers for group $groupEmail", t)
      false
    }

  // Stream all rows from a keyset-paginated query, starting after `initialCursor`, fetching the next page once the current one is exhausted.
  private def pagedStream[A, K](initialCursor: Option[K])(fetchPage: Option[K] => IO[Seq[A]])(key: A => K): Stream[IO, A] =
    Stream
      .unfoldEval(initialCursor) { cursor =>
        fetchPage(cursor).map { page =>
          if (page.isEmpty) None else Some((page, Option(key(page.last))))
        }
      }
      .flatMap(page => Stream.emits(page))
}

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
