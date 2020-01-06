package org.broadinstitute.dsde.workbench.sam.db.tables

import org.broadinstitute.dsde.workbench.sam.db.DatabaseNames
import scalikejdbc._

trait SQLSyntaxSupportWithDefaultSamDB[A] extends SQLSyntaxSupport[A] {
  // I think this connection pool is used only to pull table metadata from the database (which is cached).
  override def connectionPoolName: Any = DatabaseNames.Foreground.name
}
