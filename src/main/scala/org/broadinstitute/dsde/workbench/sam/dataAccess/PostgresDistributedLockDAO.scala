package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.data.OptionT
import cats.effect.{Async, Resource}
import cats.syntax.all._
import fs2.Stream
import org.broadinstitute.dsde.workbench.model._
import org.broadinstitute.dsde.workbench.sam.db.SamParameterBinderFactory._
import org.broadinstitute.dsde.workbench.sam.db._
import org.broadinstitute.dsde.workbench.sam.db.tables._
import org.broadinstitute.dsde.workbench.sam.util.lock.DistributedLockAlgebra
import org.broadinstitute.dsde.workbench.sam.util.DatabaseSupport

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.FiniteDuration

class PostgresDistributedLockDAO[F[_]](
  protected val writeDbRef: DbReference,
  protected val readDbRef: DbReference,
  config: DistributedLockConfig
)(implicit F: Async[F]) extends DistributedLockAlgebra[F] with DatabaseSupport {

  val retryable: Throwable => Boolean = {
    case _: FailToObtainLock => true
    case _                   => false
  }

  def withLock(lock: LockDetails): Resource[F, Unit] = {
      Resource.make {
        Stream
          .retry[F, Unit](acquireLock(lock), config.retryInterval, identity, config.maxRetry, retryable)
          .adaptError { case e => new WorkbenchException(s"Reached max retry: $e") }
          .compile
          .drain
      }(_ => releaseLock(lock))
  }

  private[dsde] def acquireLock(lock: LockDetails): F[Unit] = {
    for {
      lockStatus <- getLockStatus(lock)
      _ <- lockStatus match {
        case Available =>
          setLock(lock).recoverWith(_ => F.raiseError[Unit](FailToObtainLock(lock)))
        case Locked    => F.raiseError[Unit](FailToObtainLock(lock))
      }
    } yield ()
  }

  private[dsde] def getLockStatus(lock: LockDetails): F[LockStatus] = {
    val res = for {
      lockRecord <- OptionT(F.delay(retrieveLock(lock)))
      expiresAt <- OptionT.fromOption[F](Option(lockRecord.expiresAt))
      statusF = F.realTimeInstant.map[LockStatus] { currentTime =>
        if (currentTime.isAfter(expiresAt)) {
          deleteLock(lock)
          Available
        } else
          Locked
      }
      status <- OptionT.liftF[F, LockStatus](statusF)
    } yield status
    res.fold[LockStatus](Available)(identity)
  }

  private[dsde] def setLock(lock: LockDetails): F[Unit] = {
    for {
      currentTime <- F.realTimeInstant
      expiration = currentTime.plus(lock.expiresIn.toMillis, ChronoUnit.MILLIS)
      _ <- F.delay(createLock(lock, expiration))
    } yield ()
  }

  private[dsde] def releaseLock(lock: LockDetails): F[Unit] = {
    F.delay(deleteLock(lock))
  }

  private[dsde] def retrieveLock(lock: LockDetails): Option[DistributedLockRecord] = {
    readDbRef.inLocalTransaction(implicit session => {
      val dl = DistributedLockTable.syntax("dl")
      val column = DistributedLockTable.column
      samsql"""select ${dl.resultAll}
        from ${DistributedLockTable as dl}
        where ${column.lockName} = ${lock.lockName} and ${column.lockValue} = ${lock.lockValue}"""
        .map(DistributedLockTable(dl)).single().apply()
    })
  }

  private[dsde] def createLock(lock: LockDetails, expiresAt: Instant): Int = {
    writeDbRef.inLocalTransaction(implicit session => {
      val dl = DistributedLockTable.column
      val insertLockQuery =
        samsql"""insert into ${DistributedLockTable.table}
                 (${dl.lockName}, ${dl.lockValue}, ${dl.expiresAt})
                 values (${lock.lockName}, ${lock.lockValue}, $expiresAt)"""
      insertLockQuery.update().apply()
    })
  }

  private[dsde] def deleteLock(lock: LockDetails): Unit = {
    writeDbRef.inLocalTransaction(implicit session => {
      val dl = DistributedLockTable.column
      samsql"""delete from ${DistributedLockTable.table}
      where ${dl.lockName} = ${lock.lockName} and ${dl.lockValue} = ${lock.lockValue}"""
        .update().apply()
    })
  }
}

object PostgresDistributedLockDAO {
  def apply[F[_]: Async](
      writeDbRef: DbReference,
      readDbRef: DbReference,
      config: DistributedLockConfig): PostgresDistributedLockDAO[F] =
    new PostgresDistributedLockDAO[F](writeDbRef, readDbRef, config)
}


final case class DistributedLockConfig(retryInterval: FiniteDuration, maxRetry: Int)
final case class FailToObtainLock(lock: LockDetails) extends RuntimeException {
  override def getMessage: String = s"can't get lock: $lock"
}

sealed trait LockStatus extends Serializable with Product
final case object Available extends LockStatus
final case object Locked extends LockStatus

final case class LockDetails(lockName: String, lockValue: String, expiresIn: FiniteDuration)