package org.broadinstitute.dsde.workbench.sam.util

import cats.effect.{ContextShift, IO}
import io.opencensus.trace.Span
import org.broadinstitute.dsde.workbench.sam.db.DbReference
import scalikejdbc.DBSession

import scala.concurrent.ExecutionContext

import OpenCensusIOUtils._

trait DatabaseSupport {
  protected val ecForDatabaseIO: ExecutionContext
  protected val cs: ContextShift[IO]
  protected val dbRef: DbReference

  protected def runInTransaction[A](databaseFunction: DBSession => A, parentSpan: Span = null): IO[A] = traceIOWithParent("sam_db_contextSwitch", parentSpan) { childSpan =>
    cs.evalOn(ecForDatabaseIO)(traceIOWithParent("sam_db_transaction", childSpan) { _ =>
      IO {
        dbRef.inLocalTransaction(databaseFunction)
      }
    })
  }
}
