package org.broadinstitute.dsde.workbench.sam.util.lock

import cats.effect.Resource
import org.broadinstitute.dsde.workbench.sam.dataAccess.Lock

trait DistributedLockAlgebra[F[_]] {
  def withLock(lock: Lock): Resource[F, Unit]
}
