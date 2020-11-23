package org.broadinstitute.dsde.workbench.sam.directory

import cats.effect.{ContextShift, IO}

import org.broadinstitute.dsde.workbench.sam.db._

import scala.concurrent.ExecutionContext

/**
  * Replace the "classic" hierarchical/recursive model with a flattened DB structure optimized for quick reads.
  *
  * For the design, see Appendix A in https://docs.google.com/document/d/1pXAhic_GxM-G9qBFTk0JiTF9YY4nUJfdUEWvbkd_rNw
  */
class FlatPostgresDirectoryDAO (override val dbRef: DbReference, override val ecForDatabaseIO: ExecutionContext)
                               (implicit override val cs: ContextShift[IO]) extends PostgresDirectoryDAO(dbRef, ecForDatabaseIO) {
}
