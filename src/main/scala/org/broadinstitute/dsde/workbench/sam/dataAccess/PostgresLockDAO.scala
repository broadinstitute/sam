package org.broadinstitute.dsde.workbench.sam.dataAccess

import akka.http.scaladsl.model.StatusCodes
import cats.effect.{IO, Temporal}
import com.typesafe.scalalogging.LazyLogging
import java.util.UUID
import org.broadinstitute.dsde.workbench.sam.errorReportSource
import org.broadinstitute.dsde.workbench.model.{ErrorReport, WorkbenchExceptionWithErrorReport}
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._
import org.broadinstitute.dsde.workbench.sam.db._
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.model.api.SamLock
import org.broadinstitute.dsde.workbench.sam.util.{DatabaseSupport, SamRequestContext}
import org.postgresql.util.PSQLException
import scala.util.{Failure, Try}

class PostgresLockDAO(protected val writeDbRef: DbReference, protected val readDbRef: DbReference)(implicit timer: Temporal[IO])
    extends LockDao
    with DatabaseSupport
    with LazyLogging {

  override def create(lock: SamLock, samRequestContext: SamRequestContext): IO[SamLock] =
    serializableWriteTransaction("createLock", samRequestContext) { implicit session =>
      val lockTableColumn = LockTable.column
      val insertLockQuery =
        samsql"""insert into ${LockTable.table} (${lockTableColumn.id}, ${lockTableColumn.description}, ${lockTableColumn.lock_type}, ${lockTableColumn.lock_detail})
             values (${lock.id}, ${lock.description}, ${lock.lockType}, ${lock.lockDetails.toString()}::jsonb)"""
      Try {
        insertLockQuery.update().apply()
      }.recoverWith {
        case duplicateException: PSQLException if duplicateException.getSQLState == PSQLStateExtensions.UNIQUE_VIOLATION =>
          Failure(new WorkbenchExceptionWithErrorReport(ErrorReport(StatusCodes.Conflict, s"A lock with id ${lock.id} already exists")))
      }.get
      lock
    }

  override def delete(lockId: UUID, samRequestContext: SamRequestContext): IO[Boolean] =
    serializableWriteTransaction("deleteLock", samRequestContext) { implicit session =>
      val lockTableColumn = LockTable.column
      samsql"delete from ${LockTable} where ${lockTableColumn.id} = $lockId".update().apply() > 0
    }
}
