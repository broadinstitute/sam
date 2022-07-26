package org.broadinstitute.dsde.workbench.sam.util.lock

import cats.effect.Resource
import org.broadinstitute.dsde.workbench.sam.dataAccess.LockDetails

trait DistributedLockAlgebra[F[_]] {
  def withLock(lock: LockDetails): Resource[F, Unit]
}
