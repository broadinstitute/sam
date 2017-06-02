package org.broadinstitute.dsde.workbench.sam

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import net.ceedubs.ficus.Ficus._
import org.broadinstitute.dsde.workbench.sam.api.SamRoutes
import org.broadinstitute.dsde.workbench.sam.directory._
import org.broadinstitute.dsde.workbench.sam.model.Resource
import org.broadinstitute.dsde.workbench.sam.openam.{OpenAmDAO, _}
import org.broadinstitute.dsde.workbench.sam.service.ResourceService

import scala.concurrent.Future

object Boot extends App with LazyLogging {

  private def startup(): Unit = {

    val config = ConfigFactory.load()

    val directoryConfig = config.as[DirectoryConfig]("directory")

    // we need an ActorSystem to host our application in
    implicit val system = ActorSystem("sam")
    implicit val materializer = ActorMaterializer()
    import scala.concurrent.ExecutionContext.Implicits.global

    val conf = ConfigFactory.load()

    val accessManagementDAO = new OpenAmDAO(conf.getConfig("openam").getString("url"))
    val directoryDAO = new JndiDirectoryDAO(directoryConfig)

    val resourceService = new ResourceService(accessManagementDAO, directoryDAO)

    def syncResourceTypes(resources: Set[Resource]): Unit = {
      logger.warn("Syncing resource types...")
      Future.traverse(resources) {
        resourceService.createResourceType
      }
    }

    //Before booting, sync resource types in config with OpenAM
    val resourceTypes = config.as[Set[Resource]]("resourceTypes")
    syncResourceTypes(resourceTypes)

    Http().bindAndHandle(new SamRoutes(resourceService).route, "localhost", 8080)
  }

  startup()
}
