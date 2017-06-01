package org.broadinstitute.dsde.workbench.sam

import net.ceedubs.ficus.readers.ValueReader

/**
  * Created by dvoet on 5/26/17.
  */
package object directory {
  implicit val directoryConfigReader: ValueReader[DirectoryConfig] = ValueReader.relative { config =>
    DirectoryConfig(
      config.getString("url"),
      config.getString("user"),
      config.getString("password"),
      config.getString("baseDn")
    )
  }
}
