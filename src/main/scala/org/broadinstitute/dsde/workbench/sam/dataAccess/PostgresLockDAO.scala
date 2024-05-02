package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.{IO, Temporal}
import com.typesafe.scalalogging.LazyLogging
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._
import org.broadinstitute.dsde.workbench.sam.db._
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.model.api.SamLock
import org.broadinstitute.dsde.workbench.sam.util.{DatabaseSupport, SamRequestContext}
import scalikejdbc._

class PostgresLockDAO(protected val writeDbRef: DbReference, protected val readDbRef: DbReference)(implicit timer: Temporal[IO])
    extends LockDao
    with DatabaseSupport
    with LazyLogging {

  override def create(lock: SamLock, samRequestContext: SamRequestContext): IO[SamLock] =
    serializableWriteTransaction("createLock", samRequestContext) { implicit session =>
      insertLock(lock)
    }

  private def insertLock(lock: SamLock)(implicit session: DBSession): SamLock = {
    val lockTableColumn = LockTable.column
    val insertLockQuery =
      samsql"""insert into ${LockTable.table} (${lockTableColumn.id}, ${lockTableColumn.description}, ${lockTableColumn.lock_type}, ${lockTableColumn.lock_detail})
           values (${lock.id}, ${lock.description}, ${lock.lockType}, ${lock.lockDetails.toString()}::jsonb)"""
    insertLockQuery.update().apply()
    lock
  }

}
