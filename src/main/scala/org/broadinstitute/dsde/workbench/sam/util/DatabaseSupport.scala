package org.broadinstitute.dsde.workbench.sam.util

import cats.effect.{ContextShift, IO}
import io.opencensus.trace.Span
import org.broadinstitute.dsde.workbench.sam.db.DbReference
import org.broadinstitute.dsde.workbench.sam.util.OpenCensusIOUtils.traceIOWithParent
import scalikejdbc.DBSession

import scala.concurrent.ExecutionContext

trait DatabaseSupport {
  protected val ecForDatabaseIO: ExecutionContext
  protected val cs: ContextShift[IO]
  protected val dbRef: DbReference

  protected def runInTransaction[A](dbQueryName: String = "dbCall", parentSpan: Span = null)(databaseFunction: DBSession => A): IO[A] = {
    val spanName = "postgres-" + dbQueryName
    traceIOWithParent(spanName, parentSpan) { _ =>
      cs.evalOn(ecForDatabaseIO)(IO {
        dbRef.inLocalTransaction(databaseFunction)
      })
    }
  }
}
