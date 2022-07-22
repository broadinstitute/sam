package org.broadinstitute.dsde.workbench.sam.util.lock

import cats.effect.Resource
import org.broadinstitute.dsde.workbench.sam.dataAccess.LockAccessor

trait DistributedLockAlgebra[F[_]] {
  def withLock(lock: LockAccessor): Resource[F, Unit]
}
