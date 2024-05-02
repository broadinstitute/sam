package org.broadinstitute.dsde.workbench.sam.dataAccess

import cats.effect.IO
import org.broadinstitute.dsde.workbench.sam.model.api.SamLock
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

trait LockDao {
  def create(lock: SamLock, samRequestContext: SamRequestContext): IO[SamLock]
}
