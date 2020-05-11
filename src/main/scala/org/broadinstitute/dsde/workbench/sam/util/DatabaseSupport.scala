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

  /**
    * Executes postgres query.
    *
    * @param dbQueryName name of the database query. Used to identify the name of the tracing span.
    * @param samRequestContext context of the request. If it contains a parentSpan, then a child span will be
    *                          created under the parent span.
    */
  protected def runInTransaction[A](dbQueryName: String, samRequestContext: SamRequestContext)(databaseFunction: DBSession => A): IO[A] = {
    val spanName = "postgres-" + dbQueryName
    traceIOWithContext(spanName, samRequestContext) { _ =>
      cs.evalOn(ecForDatabaseIO)(IO {
        dbRef.inLocalTransaction(databaseFunction)
      })
    }
  }
}
