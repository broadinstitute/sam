package org.broadinstitute.dsde.workbench.sam.util

import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.sam.db.DbReference
import org.broadinstitute.dsde.workbench.sam.util.OpenCensusIOUtils.traceIOWithContext
import scalikejdbc.DBSession

import scala.concurrent.ExecutionContext

trait DatabaseSupport {
  protected val ecForDatabaseIO: ExecutionContext
  protected val cs: ContextShift[IO]
  protected val dbRef: DbReference

  protected def runInTransaction[A](dbQueryName: String, samRequestContext: SamRequestContext)(databaseFunction: DBSession => A): IO[A] = {
    val spanName = "postgres-" + dbQueryName
    traceIOWithContext(spanName, samRequestContext) { _ =>
      cs.evalOn(ecForDatabaseIO)(IO {
        dbRef.inLocalTransaction(databaseFunction)
      })
    }
  }
}
