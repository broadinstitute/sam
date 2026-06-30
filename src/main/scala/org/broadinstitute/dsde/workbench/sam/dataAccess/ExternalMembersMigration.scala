package org.broadinstitute.dsde.workbench.sam.dataAccess

import java.time.Instant

/** Progress of the one-off allowExternalMembers migration for a single tier. See GoogleGroupExternalMembersMigrator. */
final case class ExternalMembersMigrationRecord(
    tier: String,
    state: String,
    total: Option[Long],
    processed: Long,
    failed: Long,
    lastCursor: Option[String],
    startedAt: Instant,
    updatedAt: Instant
)

/** The lifecycle states persisted in `ExternalMembersMigrationRecord.state`. */
object MigrationState {
  val Running = "running"
  val Completed = "completed"
  val Failed = "failed"
}
