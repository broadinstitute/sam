package org.broadinstitute.dsde.workbench.sam.util

import cats.effect.IO
import io.opencensus.trace.AttributeValue
import org.broadinstitute.dsde.workbench.sam.db.{DbReference, PSQLStateExtensions}
import org.broadinstitute.dsde.workbench.util.addJitter
import org.postgresql.util.PSQLException
import scalikejdbc._

import scala.concurrent.duration._
import cats.effect.Temporal

trait DatabaseSupport {
  protected val writeDbRef: DbReference
  protected val readDbRef: DbReference

  protected def readOnlyTransaction[A](dbQueryName: String, samRequestContext: SamRequestContext)(databaseFunction: DBSession => A): IO[A] = {
    val databaseIO = IO(readDbRef.readOnly(databaseFunction))
    readDbRef.runDatabaseIO(dbQueryName, samRequestContext, databaseIO)
  }

  /** Run a database transaction with isolation level set to serializable. See https://www.postgresql.org/docs/9.6/transaction-iso.html. Use this when you need
    * to be sure there are no concurrent changes that affect the outcome of databaseFunction. When a transaction fails due to a serialization failure it may be
    * retried.
    *
    * Serializable transactions are used primarily by updates to the hierarchical group and resource/policy models to avoid race conditions where there are
    * concurrent changes to those structures.
    *
    * Example: group A contains group B contains group C, add user U to C at the same time as removing B from A. This is not something a typical database
    * locking strategy can account for as they are seemingly unrelated changes. However sam tries to flatten this group structure which requires a stable view
    * of all of these records.
    *
    * @param dbQueryName
    *   name used in traces
    * @param samRequestContext
    * @param maxTries
    *   how many time in total should this transaction be tried
    * @param databaseFunction
    *   function that performs the function within the transaction
    * @param timer
    *   used for scheduling retries
    * @tparam A
    *   return type
    * @return
    */
  protected def serializableWriteTransaction[A](dbQueryName: String, samRequestContext: SamRequestContext, maxTries: Int = 100)(
      databaseFunction: DBSession => A
  )(implicit timer: Temporal[IO]): IO[A] = {
    val transactionIO = IO(writeDbRef.inLocalTransactionWithIsolationLevel(IsolationLevel.Serializable) { session =>
      databaseFunction(session)
    })
    attemptSerializableTransaction(samRequestContext, dbQueryName, transactionIO, maxTries)
  }

  /** Attempts to run a serializable transaction, retrying any serialization failures with a total number of maxTries, doubling the sleep duration between each
    * try.
    */
  private def attemptSerializableTransaction[A](
      samRequestContext: SamRequestContext,
      dbQueryName: String,
      transactionIO: IO[A],
      maxTries: Int,
      trialNumber: Int = 1,
      sleepDuration: FiniteDuration = 10 millis
  )(implicit timer: Temporal[IO]): IO[A] =
    writeDbRef
      .runDatabaseIO(dbQueryName, samRequestContext, transactionIO, Map("trial" -> AttributeValue.longAttributeValue(trialNumber.longValue())))
      .handleErrorWith {
        case psqlE: PSQLException if psqlE.getSQLState == PSQLStateExtensions.SERIALIZATION_FAILURE && trialNumber < maxTries =>
          timer.sleep(addJitter(sleepDuration, sleepDuration / 2)) *> attemptSerializableTransaction(
            samRequestContext,
            dbQueryName,
            transactionIO,
            maxTries,
            trialNumber + 1,
            sleepDuration
          )

        case regrets => IO.raiseError(regrets)
      }
}
