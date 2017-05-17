package org.broadinstitute.dsde.workbench.sam.api

import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._

/**
  * Created by dvoet on 5/17/17.
  */
object SamRoutes {
  val route =
    path("hello") {
      get {
        complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
      }
    }
}
