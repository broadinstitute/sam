package org.broadinstitute.dsde.workbench.sam.db.tables

import scalikejdbc._

trait SQLSyntaxSupportWithDefaultSamDB[A] extends SQLSyntaxSupport[A] {
  // I think this connection pool is used only to pull table metadata from the database (which is cached).
  override def connectionPoolName: Any = 'sam_foreground
}
