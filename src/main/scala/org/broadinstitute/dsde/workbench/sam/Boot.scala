package org.broadinstitute.dsde.workbench.sam

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import org.broadinstitute.dsde.workbench.sam.api.SamRoutes

object Boot extends App with LazyLogging {
  private def startup(): Unit = {
    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("sam")
    implicit val materializer = ActorMaterializer()


    val bindingFuture = Http().bindAndHandle(SamRoutes.route, "localhost", 8080)
  }

  startup()
}
