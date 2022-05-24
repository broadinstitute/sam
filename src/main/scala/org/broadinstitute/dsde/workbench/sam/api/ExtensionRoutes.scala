package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.server
import org.broadinstitute.dsde.workbench.sam.model.SamUser
import org.broadinstitute.dsde.workbench.sam.util.SamRequestContext

trait ExtensionRoutes {
  def extensionRoutes(samUser: SamUser, samRequestContext: SamRequestContext): server.Route
}
