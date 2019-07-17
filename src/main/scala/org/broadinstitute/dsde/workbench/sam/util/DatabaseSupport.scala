package org.broadinstitute.dsde.workbench.sam.util

import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.sam.db.DbReference
import scalikejdbc.DBSession

import scala.concurrent.ExecutionContext

trait DatabaseSupport {
  protected val ecForDatabaseIO: ExecutionContext
  protected val cs: ContextShift[IO]
  protected val dbRef: DbReference

  protected def runInTransaction[A](databaseFunction: DBSession => A): IO[A] = {
    cs.evalOn(ecForDatabaseIO)(IO {
      dbRef.inLocalTransaction(databaseFunction)
    })
  }
}
