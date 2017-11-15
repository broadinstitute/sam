package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.server

trait ExtensionRoutes {
  def extensionRoutes: server.Route
}
