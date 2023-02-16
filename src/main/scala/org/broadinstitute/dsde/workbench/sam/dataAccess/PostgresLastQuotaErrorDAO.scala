package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO
import org.broadinstitute.dsde.workbench.sam.db.DbReference
import org.broadinstitute.dsde.workbench.sam.db.tables.LastQuotaErrorTable
import org.broadinstitute.dsde.workbench.sam.util.{DatabaseSupport, SamRequestContext}
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._

import scala.concurrent.duration.Duration

class PostgresLastQuotaErrorDAO(protected val writeDbRef: DbReference, protected val readDbRef: DbReference)
    extends LastQuotaErrorDAO
    with DatabaseSupport
    with PostgresGroupDAO {

  private val recordId = 1

  override def quotaErrorOccurredWithinDuration(duration: Duration): IO[Boolean] =
    readOnlyTransaction("quotaErrorOccurredWithinDuration", SamRequestContext()) { implicit session =>
      val lqe = LastQuotaErrorTable.syntax("lqe")
      // doing the date math and comparison within the query avoid possible timezone/skew problems between app and db
      samsql"""select ${lqe.resultAll} from ${LastQuotaErrorTable as lqe} where
              ${lqe.lastQuotaError} + ${duration.toMillis} * INTERVAL '1 milliseconds' > clock_timestamp()"""
        .map(LastQuotaErrorTable.apply(lqe))
        .single()
        .apply()
        .isDefined
    }

  override def recordQuotaError(): IO[Int] =
    serializableWriteTransaction("recordQuotaError", SamRequestContext()) { implicit session =>
      val lqeColumn = LastQuotaErrorTable.column
      // note that this uses the clock_timestamp() postgres function instead of Instant.now()
      // because this transaction could be retried and we want to record really now
      // and not the time when the query was formulated
      samsql"""insert into ${LastQuotaErrorTable.table}(${lqeColumn.id}, ${lqeColumn.lastQuotaError}) values($recordId, clock_timestamp())
              on conflict (${lqeColumn.id}) do update
              set ${lqeColumn.lastQuotaError} = EXCLUDED.${lqeColumn.lastQuotaError}
            """.update().apply()
    }
}
