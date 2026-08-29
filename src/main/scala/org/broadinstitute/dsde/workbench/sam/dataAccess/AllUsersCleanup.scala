package org.broadinstitute.dsde.workbench.sam.dataAccess

import java.time.Instant

/** Progress of an All_Users cleanup run for a single email pattern. See AllUsersGroupCleanupMigrator. */
final case class AllUsersCleanupRecord(
    emailPattern: String,
    state: String,
    total: Option[Long],
    processed: Long,
    failed: Long,
    lastCursor: Option[String],
    startedAt: Instant,
    updatedAt: Instant
)
