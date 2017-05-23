package org.broadinstitute.dsde.workbench.sam

import akka.actor.ActorSystem
import com.typesafe.scalalogging.LazyLogging
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import org.broadinstitute.dsde.workbench.sam.api.SamRoutes
import net.ceedubs.ficus.Ficus._
import directory._
import org.broadinstitute.dsde.workbench.sam.dataaccess.{AccessManagementDAO, DirectoryDAO}
import org.broadinstitute.dsde.workbench.sam.service.ResourceService

object Boot extends App with LazyLogging {
  private def startup(): Unit = {

    val config = ConfigFactory.load()

    val directoryConfig = config.as[DirectoryConfig]("directory")

    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("sam")
    implicit val materializer = ActorMaterializer()
    import scala.concurrent.ExecutionContext.Implicits.global

    val conf = ConfigFactory.load()

    val acessManagementDAO = new AccessManagementDAO(conf.getConfig("openam").getString("server"))
    val directoryDAO = new DirectoryDAO(conf.getConfig("opendj").getString("server"))

    val resourceService = new ResourceService(acessManagementDAO, directoryDAO)

    Http().bindAndHandle(new SamRoutes(resourceService).routes, "localhost", 8080)
  }

  startup()
}
