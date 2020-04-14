package org.broadinstitute.dsde.workbench.sam.util

import cats.effect.{ContextShift, IO}
import org.broadinstitute.dsde.workbench.sam.db.DbReference
import org.broadinstitute.dsde.workbench.sam.util.OpenCensusIOUtils.traceIOWithParent
import scalikejdbc.DBSession

import scala.concurrent.ExecutionContext

trait DatabaseSupport {
  protected val ecForDatabaseIO: ExecutionContext
  protected val cs: ContextShift[IO]
  protected val dbRef: DbReference

  protected def runInTransaction[A](databaseFunction: DBSession => A): IO[A] = {
    val spanName = "postgres-" + parentMethodName()
    traceIOWithParent (spanName, null) ( _ =>
      cs.evalOn(ecForDatabaseIO)(IO {
        dbRef.inLocalTransaction(databaseFunction)
      })
    )
  }

  /*
  This gets the name of the method that calls runInTransaction. It goes 5 levels up because the stack looks like this:
  loadSubjectFromGoogleSubjectId->runInTransaction->runInTransaction$->runInTransaction->parentMethodName
   */
  private def parentMethodName() : String = Thread.currentThread.getStackTrace()(5).getMethodName
}
